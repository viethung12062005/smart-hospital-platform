"""Build the Keycloak `shp` realm for each environment from one source (IAM-01, ADR-0017).

Source of truth: identity/realm/base.json (everything every environment shares) plus one overlay per environment
(identity/realm/overlay-<env>.json). Generated files, never edited by hand:

  local -> dev/chart/shp-local-deps/files/realm-shp.json      (kind cluster; the dev-only client shp-test lives only here)
  prod  -> identity/realm/dist/realm-shp-prod.json            (uat and prod; copied into shp-gitops, see below)

Overlay merge rules: objects merge recursively; `clients` merge by `clientId` (a client that only the overlay names is
added); every other list is replaced. Environment-specific values that must not be in git are `${VARIABLE}`
placeholders, which Keycloak substitutes from the pod environment when it imports the realm.

  python3 -I tools/identity/realm.py            # write the generated files
  python3 -I tools/identity/realm.py --check    # CI: fail when a generated file drifts from its source
  python3 -I tools/identity/realm.py --check --gitops ../shp-gitops   # also compare the copy used by Argo CD
"""

import argparse
import copy
import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "identity" / "realm"
TARGETS = {
    "local": ROOT / "dev" / "chart" / "shp-local-deps" / "files" / "realm-shp.json",
    "prod": SRC / "dist" / "realm-shp-prod.json",
}
GITOPS_COPY = Path("platform") / "charts" / "shp-keycloak" / "files" / "realm-shp.json"
# The password blacklist (policy passwordBlacklist) is a plain file Keycloak reads from its data directory.
BLACKLIST = SRC / "breached-passwords.txt"
BLACKLIST_TARGETS = [
    ROOT / "dev" / "chart" / "shp-local-deps" / "files" / "breached-passwords.txt",
    SRC / "dist" / "breached-passwords.txt",
]
GITOPS_BLACKLIST = (
    Path("platform") / "charts" / "shp-keycloak" / "files" / "breached-passwords.txt"
)
# Content that must never reach a realm that runs in a cluster (checked by `forbidden`).
FORBIDDEN_IN_PROD = ("localhost", "shp-test")
LITERAL_CREDENTIAL = re.compile(
    r'"(?:password|secret|value|secretData)"\s*:\s*"(?!\$\{)'
)


def merge(base: Any, over: Any) -> Any:
    if isinstance(base, dict) and isinstance(over, dict):
        out = copy.deepcopy(base)
        for key, value in over.items():
            if key == "clients" and isinstance(value, list):
                out[key] = merge_clients(base.get(key, []), value)
            else:
                out[key] = (
                    merge(base[key], value) if key in base else copy.deepcopy(value)
                )
        return out
    return copy.deepcopy(over)


def merge_clients(
    base: list[dict[str, Any]], over: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    out = copy.deepcopy(base)
    index = {c["clientId"]: i for i, c in enumerate(out)}
    for client in over:
        if client["clientId"] in index:
            out[index[client["clientId"]]] = merge(
                out[index[client["clientId"]]], client
            )
        else:
            out.append(copy.deepcopy(client))
    return out


def service_accounts(realm: dict[str, Any]) -> None:
    """Expand identity/realm/services.json into roles, confidential clients and their service-account users.

    `serviceClientAuth` (an overlay key, removed here) selects how a client proves who it is: `client-secret` (Keycloak
    generates the secret on import; it is never in git) or `federated-jwt` (a Kubernetes service-account token, IAM-02).

    Audience is an isolation boundary (ADR-0017, 15 section 1): a service token names **only** the services it is
    allowed to call (`svc-<target>`), never `shp-api`, which is the audience of user tokens. A leaked service token
    therefore opens nothing beyond its own callers, and a user endpoint cannot be reached with it even before the
    role check.
    """
    spec = json.loads((SRC / "services.json").read_text(encoding="utf-8"))
    auth = realm.pop("serviceClientAuth", {"type": "client-secret"})
    targets = sorted({t for svc in spec["services"].values() for t in svc["calls"]})
    realm["roles"]["realm"] += [
        {"name": f"internal.{t}", "description": f"May call /internal of {t}"}
        for t in targets
    ]
    users: list[dict[str, Any]] = realm.setdefault("users", [])
    for name, svc in spec["services"].items():
        client_id = f"svc-{name}"
        attributes: dict[str, str] = {}
        if auth["type"] == "federated-jwt":
            attributes = {
                "jwt.credential.issuer": auth["identityProvider"],
                "jwt.credential.sub": f"system:serviceaccount:{spec['namespace']}:{name}",
            }
        exchange = svc.get("exchange", [])
        if exchange:  # standard token exchange v2 (RFC 8693): this client may exchange a user token (IAM-07)
            attributes["standard.token.exchange.enabled"] = "true"
        # Audience isolation (ADR-0017): the targets only, never shp-api. Keycloak only lets a client exchange into
        # an audience that is already available to it ("Requested audience not available"), so the exchange targets
        # are here too; that token can reach them but carries the internal role only for `calls`.
        audiences = list(dict.fromkeys(f"svc-{t}" for t in [*svc["calls"], *exchange]))
        realm["clients"].append(
            {
                "clientId": client_id,
                "name": f"Service account of {name} (/internal)",
                "enabled": True,
                "publicClient": False,
                "clientAuthenticatorType": auth["type"],
                "serviceAccountsEnabled": True,
                "standardFlowEnabled": False,
                "implicitFlowEnabled": False,
                "directAccessGrantsEnabled": False,
                "attributes": attributes,
                "defaultClientScopes": ["basic"],
                "protocolMappers": [
                    {
                        "name": "roles-claim",
                        "protocol": "openid-connect",
                        "protocolMapper": "oidc-usermodel-realm-role-mapper",
                        "config": {
                            "claim.name": "roles",
                            "jsonType.label": "String",
                            "multivalued": "true",
                            "access.token.claim": "true",
                            "id.token.claim": "false",
                            "userinfo.token.claim": "false",
                        },
                    },
                    *(
                        # the exchanged token of a user keeps the authentication methods of the user's session (IAM-10)
                        [
                            {
                                "name": "amr-claim",
                                "protocol": "openid-connect",
                                "protocolMapper": "oidc-amr-mapper",
                                "config": {
                                    "id.token.claim": "false",
                                    "access.token.claim": "true",
                                },
                            }
                        ]
                        if exchange
                        else []
                    ),
                    *[
                        {
                            "name": f"audience-{a}",
                            "protocol": "openid-connect",
                            "protocolMapper": "oidc-audience-mapper",
                            "config": {
                                "included.custom.audience": a,
                                "access.token.claim": "true",
                                "id.token.claim": "false",
                            },
                        }
                        for a in audiences
                    ],
                ],
            }
        )
        users.append(
            {
                "username": f"service-account-{client_id}",
                "enabled": True,
                "serviceAccountClientId": client_id,
                "realmRoles": [f"internal.{t}" for t in svc["calls"]],
            }
        )


def exchange_audience(realm: dict[str, Any]) -> None:
    """User-facing clients put `svc-<name>` in the audience of the user's token for every service that exchanges it.

    Standard token exchange only accepts a subject token whose audience contains the requesting client (Keycloak docs,
    "Standard token exchange"), so without this mapper the exchange of a user token is refused.
    """
    spec = json.loads((SRC / "services.json").read_text(encoding="utf-8"))
    requesters = [f"svc-{n}" for n, s in spec["services"].items() if s.get("exchange")]
    for client in realm["clients"]:
        if client["clientId"] in ("shp-web", "shp-test"):
            for requester in requesters:
                client.setdefault("protocolMappers", []).append(
                    {
                        "name": f"audience-{requester}",
                        "protocol": "openid-connect",
                        "protocolMapper": "oidc-audience-mapper",
                        "config": {
                            "included.client.audience": requester,
                            "id.token.claim": "false",
                            "access.token.claim": "true",
                        },
                    }
                )


def authentication_flows(realm: dict[str, Any], env: str) -> None:
    """Add the realm's authentication flows (IAM-10).

    A realm import that names any flow replaces Keycloak's defaults, so identity/realm/flows.json holds the complete
    set of Keycloak 26.8.2 with the staff-MFA sub-flows: see docs/04-devops/keycloak.md section 10 for what differs from
    stock. The local realm keeps the stock flows (decision N3): the seeded staff users sign in with their password only,
    because a flow that demands a second factor from every staff role would lock a developer out of their own cluster.
    The cluster realm, which is where staff actually sign in, gets the staff-MFA flows.
    """
    flows = json.loads((SRC / "flows.json").read_text(encoding="utf-8"))
    if env == "local":
        flows = without_staff_mfa(flows)
    realm["authenticationFlows"] = flows["authenticationFlows"]
    realm["authenticatorConfig"] = flows["authenticatorConfig"]


# The IAM-10 additions to the stock flows: two sub-flows, the conditions that select them and their OTP reference values.
STAFF_MFA_FLOWS = ("Browser - Staff MFA", "Direct Grant - Staff MFA")
STAFF_MFA_CONFIGS = (
    "shp-user-is-staff",
    "shp-user-is-not-staff",
    "shp-otp-reference-mfa-staff-browser",
    "shp-otp-reference-mfa-staff-direct-grant",
)


def without_staff_mfa(flows: dict[str, Any]) -> dict[str, Any]:
    """The stock flows: the staff-MFA sub-flows, the executions that call them and their configs removed (N3)."""
    out = copy.deepcopy(flows)
    for flow in out["authenticationFlows"]:
        flow["authenticationExecutions"] = [
            execution
            for execution in flow.get("authenticationExecutions", [])
            if execution.get("flowAlias") not in STAFF_MFA_FLOWS
            and execution.get("authenticatorConfig") not in STAFF_MFA_CONFIGS
        ]
    out["authenticationFlows"] = [
        flow for flow in out["authenticationFlows"] if flow["alias"] not in STAFF_MFA_FLOWS
    ]
    out["authenticatorConfig"] = [
        config
        for config in out["authenticatorConfig"]
        if config["alias"] not in STAFF_MFA_CONFIGS
    ]
    return out


def user_profile(realm: dict[str, Any]) -> None:
    """Add the realm's user profile (IAM-08) from identity/realm/user-profile.json.

    Keycloak keeps it as a component whose config holds the profile as one JSON string. Sign-up asks for a phone number
    or an email only, so no name or email is required; username and email are read-only for users (changing them needs
    a new code, IAM-09); phoneNumber and phoneNumberVerified are the attributes the stock `phone` client scope maps.
    """
    profile = json.loads((SRC / "user-profile.json").read_text(encoding="utf-8"))
    realm.setdefault("components", {})["org.keycloak.userprofile.UserProfileProvider"] = [
        {
            "providerId": "declarative-user-profile",
            "subComponents": {},
            "config": {
                "kc.user.profile.config": [
                    json.dumps(profile, ensure_ascii=False, separators=(",", ":"))
                ]
            },
        }
    ]


def build(env: str) -> dict[str, Any]:
    base = json.loads((SRC / "base.json").read_text(encoding="utf-8"))
    overlay = json.loads((SRC / f"overlay-{env}.json").read_text(encoding="utf-8"))
    realm: dict[str, Any] = merge(base, overlay)
    service_accounts(realm)
    exchange_audience(realm)
    authentication_flows(realm, env)
    user_profile(realm)
    return realm


def render(env: str) -> str:
    return json.dumps(build(env), indent=2, ensure_ascii=False) + "\n"


def forbidden(text: str) -> list[str]:
    """Things a cluster realm must not contain: dev hosts, the dev client, users, literal credentials."""
    found = [token for token in FORBIDDEN_IN_PROD if token in text]
    users = json.loads(text).get("users", [])
    if any("serviceAccountClientId" not in u or "credentials" in u for u in users):
        found.append('"users" (only service-account users are allowed)')
    if re.search(r'"credentials"\s*:\s*\[', text):
        found.append('"credentials"')
    if LITERAL_CREDENTIAL.search(text):
        found.append("literal password/secret/value (use a ${PLACEHOLDER})")
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument(
        "--gitops",
        type=Path,
        help="checkout of shp-gitops whose copy of the prod realm is compared",
    )
    args = parser.parse_args()
    bad = 0
    for env, target in TARGETS.items():
        want = render(env)
        if env == "prod":
            hits = forbidden(want)
            if hits:
                print(f"{env}: realm contains dev-only content: {hits}")
                bad += 1
        if args.check:
            have = target.read_text(encoding="utf-8") if target.exists() else ""
            if have != want:
                print(
                    f"DRIFT: {target.relative_to(ROOT)} differs from identity/realm/ (run tools/identity/realm.py)"
                )
                bad += 1
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(want, encoding="utf-8", newline="\n")
            print(f"wrote {target.relative_to(ROOT)}")
    wanted = BLACKLIST.read_text(encoding="utf-8")
    for target in BLACKLIST_TARGETS:
        if args.check:
            if not target.exists() or target.read_text(encoding="utf-8") != wanted:
                print(
                    f"DRIFT: {target.relative_to(ROOT)} differs from identity/realm/breached-passwords.txt"
                )
                bad += 1
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(wanted, encoding="utf-8", newline="\n")
            print(f"wrote {target.relative_to(ROOT)}")
    if args.gitops:
        blacklist_copy = args.gitops / GITOPS_BLACKLIST
        if (
            not blacklist_copy.exists()
            or blacklist_copy.read_text(encoding="utf-8") != wanted
        ):
            print(
                f"DRIFT: {blacklist_copy} differs from identity/realm/breached-passwords.txt"
            )
            bad += 1
    if args.gitops:
        copy_path = args.gitops / GITOPS_COPY
        if not copy_path.exists() or copy_path.read_text(encoding="utf-8") != render(
            "prod"
        ):
            print(
                f"DRIFT: {copy_path} differs from the generated prod realm (copy identity/realm/dist/realm-shp-prod.json)"
            )
            bad += 1
    if bad:
        return 1
    if args.check:
        print("OK: generated realms match their source")
    return 0


if __name__ == "__main__":
    sys.exit(main())
