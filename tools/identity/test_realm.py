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
        if client_id.startswith("svc-"):  # how a service proves who it is (IAM-02)
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
            audiences = {
                m["config"]["included.custom.audience"]
                for m in client["protocolMappers"]
                if "audience" in m["name"]
            }
            assert audiences == {"shp-api", *svc["calls"]}
        roles = {r["name"] for r in realm.build(env)["roles"]["realm"]}
        assert {
            f"internal.{t}" for s in spec["services"].values() for t in s["calls"]
        } <= roles


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
