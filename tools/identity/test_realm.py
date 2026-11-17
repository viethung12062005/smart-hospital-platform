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
    assert "users" not in json.loads(text)


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
    assert (
        local["accessTokenLifespan"] == prod["accessTokenLifespan"] <= 900
    )  # ADR-0017: 15 minutes at most
    shared = set(clients("local")) & set(clients("prod"))
    assert {"shp-web", "shp-api"} <= shared
    for client_id in shared:
        a, b = dict(clients("local")[client_id]), dict(clients("prod")[client_id])
        for key in (
            "redirectUris",
            "webOrigins",
        ):  # the only fields an environment may change
            a.pop(key, None)
            b.pop(key, None)
        assert a == b, (
            f"{client_id} differs between local and prod beyond redirect URIs"
        )


def test_forbidden_detects_literal_credentials() -> None:
    assert realm.forbidden('{"secret": "abc"}')
    assert not realm.forbidden('{"secret": "${SVC_SECRET}"}')
    assert realm.forbidden('{"users": []}') == ['"users"']
