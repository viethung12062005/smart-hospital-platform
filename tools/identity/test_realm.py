"""Tests of the realm generator (IAM-01): run with `python3 -m pytest tools/identity` (needs pytest)."""

import importlib.util
import json
from pathlib import Path
from typing import Any

_spec = importlib.util.spec_from_file_location(
    "realm", Path(__file__).with_name("realm.py")
)
assert _spec is not None and _spec.loader is not None
realm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(realm)


def clients(env: str) -> dict[str, dict[str, Any]]:
    return {c["clientId"]: c for c in realm.build(env)["clients"]}


def test_generated_files_match_source() -> None:
    for env, target in realm.TARGETS.items():
        assert target.read_text(encoding="utf-8") == realm.render(env), f"{env} drifted"


def test_prod_has_no_dev_content() -> None:
    text = realm.render("prod")
    assert realm.forbidden(text) == []
    assert "shp-test" not in clients("prod")
    users = json.loads(text)["users"]
    assert users and all(
        "serviceAccountClientId" in u and "credentials" not in u for u in users
    )


def test_local_keeps_the_dev_client_and_localhost_redirects() -> None:
    assert "shp-test" in clients("local")
    assert any("localhost" in u for u in clients("local")["shp-web"]["redirectUris"])


def test_prod_redirect_uri_is_a_placeholder_not_a_host() -> None:
    web = clients("prod")["shp-web"]
    assert web["redirectUris"] == ["https://${SHP_PUBLIC_HOST}/*"]
    assert (
        web["publicClient"] is True
        and web["attributes"]["pkce.code.challenge.method"] == "S256"
    )


def test_environments_share_roles_and_every_common_client() -> None:
    local, prod = realm.build("local"), realm.build("prod")
    assert local["roles"] == prod["roles"]
    # ADR-0017: 15 minutes at most
    assert local["accessTokenLifespan"] == prod["accessTokenLifespan"] <= 900
    shared = set(clients("local")) & set(clients("prod"))
    assert {"shp-web", "shp-api"} <= shared
    for client_id in shared:
        a, b = dict(clients("local")[client_id]), dict(clients("prod")[client_id])
        per_environment = ["redirectUris", "webOrigins"]
        if client_id.startswith("svc-") or client_id == "shp-api":
            per_environment += ["clientAuthenticatorType", "attributes"]
        for key in per_environment:
            a.pop(key, None)
            b.pop(key, None)
        assert a == b, (
            f"{client_id} differs between local and prod beyond {per_environment}"
        )


def test_forbidden_detects_literal_credentials() -> None:
    assert realm.forbidden('{"secret": "abc"}')
    assert not realm.forbidden('{"secret": "${SVC_SECRET}"}')
    assert realm.forbidden('{"users": [{"username": "doctor.demo"}]}')
    assert realm.forbidden(
        '{"users": [{"serviceAccountClientId": "svc-x", "credentials": []}]}'
    )
    assert not realm.forbidden('{"users": [{"serviceAccountClientId": "svc-x"}]}')


def test_service_clients_follow_services_json_in_both_environments() -> None:
    spec = json.loads((realm.SRC / "services.json").read_text(encoding="utf-8"))
    for env in ("local", "prod"):
        built = clients(env)
        for name, svc in spec["services"].items():
            client = built[f"svc-{name}"]
            assert (
                client["serviceAccountsEnabled"] is True
                and client["publicClient"] is False
            )
            assert (
                client["standardFlowEnabled"] is False
                and client["directAccessGrantsEnabled"] is False
            )
            assert set(client["optionalClientScopes"]) == {
                f"call-{t}" for t in svc["calls"]
            }
            assert client["defaultClientScopes"] == ["basic"]
            assert all(
                m["protocolMapper"] != "oidc-audience-mapper"
                for m in client["protocolMappers"]
            )
        roles = {r["name"] for r in realm.build(env)["roles"]["realm"]}
        assert {
            f"internal.{t}" for s in spec["services"].values() for t in s["calls"]
        } <= roles


def test_service_tokens_never_carry_the_user_audience() -> None:
    for env in ("local", "prod"):
        for scope in realm.build(env)["clientScopes"]:
            target = scope["name"].split("-", 1)[1]
            [mapper] = scope["protocolMappers"]
            assert mapper["protocolMapper"] == "oidc-audience-mapper"
            assert mapper["config"]["included.custom.audience"] == f"svc-{target}"
        for client_id in ("shp-web", "shp-test"):
            client = clients(env).get(client_id)
            if client is not None:
                assert {
                    m["config"]["included.client.audience"]
                    for m in client["protocolMappers"]
                    if m["protocolMapper"] == "oidc-audience-mapper"
                } == {"shp-api"}


def test_every_java_service_that_resolves_identities_may_call_clinical() -> None:
    """SHR-32: HttpIdentityLinkClient calls clinical's GET /internal/v1/identity/{subject} (09 section 4)."""
    spec = json.loads((realm.SRC / "services.json").read_text(encoding="utf-8"))
    for caller in ("scheduling", "notification", "audit", "document"):
        assert "clinical" in spec["services"][caller]["calls"], caller
    for env in ("local", "prod"):
        built = clients(env)
        audit = built["svc-audit"]
        assert audit["serviceAccountsEnabled"] is True
        roles = {r["username"]: r["realmRoles"] for r in realm.build(env)["users"]}
        assert roles["service-account-svc-audit"] == ["internal.clinical"]


def test_client_authentication_differs_by_environment_only_in_the_method() -> None:
    assert clients("local")["svc-ai"]["clientAuthenticatorType"] == "client-secret"
    prod = clients("prod")["svc-ai"]
    assert prod["clientAuthenticatorType"] == "federated-jwt"
    assert (
        prod["attributes"]["jwt.credential.sub"] == "system:serviceaccount:shp-apps:ai"
    )
    assert "secret" not in prod
    assert (
        realm.build("prod")["identityProviders"][0]["config"]["issuer"]
        == "${SHP_K8S_OIDC_ISSUER}"
    )
    assert "identityProviders" not in realm.build("local")


def test_token_exchange_uses_the_existing_user_audience_as_a_separate_client() -> None:
    spec = json.loads((realm.SRC / "services.json").read_text(encoding="utf-8"))
    for env in ("local", "prod"):
        built = clients(env)
        enabled = {
            name
            for name, client in built.items()
            if client.get("attributes", {}).get("standard.token.exchange.enabled")
            == "true"
        }
        assert enabled == {"shp-api"}
        exchanger = built["shp-api"]
        assert not exchanger["serviceAccountsEnabled"]
        assert not exchanger["standardFlowEnabled"]
        assert not exchanger["directAccessGrantsEnabled"]
        assert set(exchanger["optionalClientScopes"]) == {
            f"exchange-{t}" for t in spec["services"]["ai"]["exchange"]
        }
    prod = clients("prod")
    assert prod["shp-api"]["attributes"]["jwt.credential.sub"] == (
        "system:serviceaccount:shp-apps:ai-exchange"
    )
    assert prod["shp-api"]["clientAuthenticatorType"] == "federated-jwt"


def test_hardening_is_in_both_realms() -> None:
    for env in ("local", "prod"):
        built = realm.build(env)
        assert (
            "hashAlgorithm(argon2)" in built["passwordPolicy"]
            and "length(12)" in built["passwordPolicy"]
        )
        assert (
            built["bruteForceProtected"] is True and built["permanentLockout"] is False
        )
        assert built["failureFactor"] == 5
        assert (
            built["adminEventsEnabled"] is True
            and built["adminEventsDetailsEnabled"] is False
        )
    assert realm.build("prod")["sslRequired"] == "external"


def test_breached_list_has_no_entry_shorter_than_the_minimum_length() -> None:
    lines = [x for x in realm.BLACKLIST.read_text(encoding="utf-8").splitlines() if x]
    assert lines and all(
        len(x) >= 12 for x in lines
    )  # shorter ones are rejected by length(12) anyway


STAFF = ("doctor", "nurse", "admin", "dpo")


def test_staff_roles_carry_the_mfa_marker_and_patient_does_not() -> None:
    for env in ("local", "prod"):
        roles = {r["name"]: r for r in realm.build(env)["roles"]["realm"]}
        assert "mfa-required" in roles
        for name in STAFF:
            assert roles[name]["composites"]["realm"] == ["mfa-required"], name
        assert "composites" not in roles["patient"]


def test_local_realm_keeps_the_stock_flows_without_staff_mfa() -> None:
    # decision N3: the local realm has no staff-MFA flow, so the seeded staff users sign in with a password only
    local = realm.build("local")
    flows = {f["alias"]: f for f in local["authenticationFlows"]}
    assert "Browser - Staff MFA" not in flows
    assert "Direct Grant - Staff MFA" not in flows
    configs = {c["alias"] for c in local["authenticatorConfig"]}
    assert not any(alias.startswith("shp-") and "staff" in alias for alias in configs)
    for flow in local["authenticationFlows"]:
        assert not any(
            e.get("flowAlias") in realm.STAFF_MFA_FLOWS
            or e.get("authenticatorConfig") in realm.STAFF_MFA_CONFIGS
            for e in flow.get("authenticationExecutions", [])
        ), flow["alias"]
    # a user who enrolled a TOTP locally is still asked for it (stock Keycloak), staff or not
    sub = {
        e["flowAlias"]
        for e in flows["forms"]["authenticationExecutions"]
        if "flowAlias" in e
    }
    assert "Browser - Conditional 2FA" in sub
    # the stock flow bindings by name must all exist, because the import replaces the defaults
    for name in (
        "browser",
        "direct grant",
        "reset credentials",
        "registration",
        "clients",
        "first broker login",
        "docker auth",
    ):
        assert name in flows


def test_prod_realm_demands_otp_from_staff_and_issues_amr() -> None:
    for env in ("prod",):
        built = realm.build(env)
        flows = {f["alias"]: f for f in built["authenticationFlows"]}
        configs = {c["alias"]: c["config"] for c in built["authenticatorConfig"]}
        sub = {
            e["flowAlias"]: e
            for e in flows["forms"]["authenticationExecutions"]
            if "flowAlias" in e
        }
        assert sub["Browser - Staff MFA"]["requirement"] == "CONDITIONAL"
        steps = flows["Browser - Staff MFA"]["authenticationExecutions"]
        assert [e["authenticator"] for e in steps] == [
            "conditional-user-role",
            "auth-otp-form",
        ]
        assert all(e["requirement"] == "REQUIRED" for e in steps)
        assert (
            configs[steps[0]["authenticatorConfig"]]["condUserRole"] == "mfa-required"
        )
        assert configs[steps[0]["authenticatorConfig"]]["negate"] == "false"
        assert (
            configs[steps[1]["authenticatorConfig"]]["default.reference.value"] == "mfa"
        )
        # the realm names no flow bindings, so Keycloak binds these defaults by name: they must exist
        for name in (
            "browser",
            "direct grant",
            "reset credentials",
            "registration",
            "clients",
            "first broker login",
            "docker auth",
        ):
            assert name in flows
        clients = {c["clientId"]: c for c in built["clients"]}
        mappers = {m["protocolMapper"] for m in clients["shp-web"]["protocolMappers"]}
        assert "oidc-amr-mapper" in mappers
    local = {c["clientId"]: c for c in realm.build("local")["clients"]}
    assert "oidc-amr-mapper" in {
        m["protocolMapper"] for m in local["shp-test"]["protocolMappers"]
    }


def test_one_password_policy_of_twelve_for_everyone() -> None:
    for env in ("local", "prod"):
        policy = realm.build(env)["passwordPolicy"]
        assert "length(12)" in policy and "length(14)" not in policy


def test_exchanging_client_carries_the_amr_mapper_and_others_do_not() -> None:
    for env in ("local", "prod"):
        for name, client in clients(env).items():
            has_amr = "oidc-amr-mapper" in {
                mapper["protocolMapper"] for mapper in client.get("protocolMappers", [])
            }
            assert has_amr == (name in {"shp-api", "shp-web", "shp-test"})


def test_login_theme_is_vietnamese_first_in_both_realms() -> None:
    for env in ("local", "prod"):
        r = realm.build(env)
        assert r["loginTheme"] == "shp", env
        assert r["internationalizationEnabled"] is True, env
        assert r["supportedLocales"] == ["vi", "en"] and r["defaultLocale"] == "vi", env
        assert r["resetPasswordAllowed"] is True, env


def test_patient_signup_flow_is_present_but_closed_until_the_otp_provider() -> None:
    expected_steps = [
        "shp-registration-identifier",
        "shp-ownership-code",
        "shp-registration-account",
    ]
    for env in ("local", "prod"):
        built = realm.build(env)
        assert built["registrationAllowed"] is False
        assert built["registrationFlow"] == "shp registration"
        flows = {flow["alias"]: flow for flow in built["authenticationFlows"]}
        signup = flows["shp registration"]
        assert [
            step["authenticator"] for step in signup["authenticationExecutions"]
        ] == (expected_steps)
        profile = built["components"]["org.keycloak.userprofile.UserProfileProvider"][
            0
        ]["config"]["kc.user.profile.config"][0]
        attributes = {item["name"] for item in json.loads(profile)["attributes"]}
        assert {"phoneNumber", "phoneNumberVerified", "terms_and_conditions"} <= (
            attributes
        )


def test_mail_server_is_the_local_catcher_or_placeholders() -> None:
    # Forgot password needs a working mail server: without one Keycloak answers an existing account with an error and an
    # unknown one with "email sent", which reveals who has an account (IAM-03).
    assert realm.build("local")["smtpServer"]["host"] == "mailpit"
    prod = realm.build("prod")["smtpServer"]
    for key in ("host", "port", "from", "user", "password"):
        assert prod[key].startswith("${SHP_SMTP_"), key
    assert prod["auth"] == "true" and prod["starttls"] == "true"
