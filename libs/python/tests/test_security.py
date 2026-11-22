import time
from typing import Annotated, Any

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from shp_platform.errors import ShpError, install_error_handlers
from shp_platform.security import Authenticator, AuthSettings, Principal, verify_token

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER = rsa.generate_private_key(public_exponent=65537, key_size=2048)
ISS = "http://localhost:18080/realms/shp"
SETTINGS = AuthSettings(authority=ISS, audience="shp-api")
# clinical runs with OIDC_AUDIENCE=svc-clinical; shp-api (user tokens) is accepted as well (ADR-0017)
CLINICAL = AuthSettings(authority=ISS, audience="svc-clinical")


def resolver(_token: str) -> Any:
    return KEY.public_key()


def token(key: Any = KEY, **overrides: Any) -> str:
    now = int(time.time())
    claims: dict[str, Any] = {
        "iss": ISS,
        "aud": "shp-api",
        "sub": "u1",
        "iat": now,
        "exp": now + 300,
        "roles": ["patient"],
    }
    claims.update(overrides)
    claims = {k: v for k, v in claims.items() if v is not None}
    return jwt.encode(claims, key, algorithm="RS256")


def test_valid_token_maps_roles_case_insensitive_and_drops_unknown() -> None:
    p = verify_token(token(roles=["Patient", "superuser"]), SETTINGS, resolver)
    assert p.subject == "u1" and p.roles == frozenset({"patient"})
    assert "eyJ" not in repr(p)  # token does not leak into repr/log


@pytest.mark.parametrize(
    "bad",
    [
        {"key": OTHER},
        {"exp": int(time.time()) - 120},
        {"aud": "other"},
        {"iss": "http://evil/realms/shp"},
        {"sub": None},
    ],
)
def test_invalid_tokens_are_unauthenticated(bad: dict[str, Any]) -> None:
    key = bad.pop("key", KEY)
    with pytest.raises(ShpError) as e:
        verify_token(token(key, **bad), SETTINGS, resolver)
    assert e.value.info.code == "unauthenticated"


def test_expired_within_clock_skew_is_accepted() -> None:
    verify_token(token(exp=int(time.time()) - 10), SETTINGS, resolver)


def test_staff_without_mfa_is_rejected_and_with_mfa_accepted() -> None:
    with pytest.raises(ShpError):
        verify_token(token(roles=["doctor"]), SETTINGS, resolver)
    assert verify_token(token(roles=["doctor"], amr=["pwd", "mfa"]), SETTINGS, resolver).is_staff
    local = AuthSettings(authority=ISS, audience="shp-api", require_mfa_for_staff=False)
    verify_token(token(roles=["doctor"]), local, resolver)


def test_unconfigured_settings_reject_everything() -> None:
    with pytest.raises(ShpError):
        verify_token(token(), AuthSettings(authority="", audience=""), resolver)


def test_service_audience_accepts_its_own_and_the_user_audience_only() -> None:
    # audience isolation (ADR-0017): user tokens carry shp-api, service tokens carry svc-<target>
    assert CLINICAL.accepted_audiences() == ("svc-clinical", "shp-api")
    assert verify_token(token(aud=["shp-api"]), CLINICAL, resolver).subject == "u1"
    p = verify_token(
        token(aud=["svc-clinical"], azp="svc-scheduling", roles=["internal.clinical"]),
        CLINICAL,
        resolver,
    )
    assert p.claims["azp"] == "svc-scheduling" and not p.roles
    for other in ("svc-document", "svc-scheduling"):
        with pytest.raises(ShpError) as e:
            verify_token(token(aud=[other]), CLINICAL, resolver)
        assert e.value.info.code == "unauthenticated"


def test_gateway_keeps_the_user_audience_only() -> None:
    # a user-facing service (gateway) sets no service audience: only shp-api is accepted
    assert AuthSettings(authority=ISS, audience="shp-api").accepted_audiences() == ("shp-api",)
    assert AuthSettings(authority=ISS, audience="").accepted_audiences() == ("shp-api",)


def _app() -> TestClient:
    auth = Authenticator(SETTINGS, resolver)
    app = FastAPI()
    install_error_handlers(app)

    @app.get("/me")
    def me(p: Annotated[Principal, Depends(auth.principal)]) -> dict[str, str]:
        return {"sub": p.subject}

    @app.get("/staff")
    def staff(p: Annotated[Principal, Depends(auth.require_roles("doctor", "nurse"))]) -> dict[str, str]:
        return {"sub": p.subject}

    return TestClient(app)


def test_fastapi_dependencies_return_problem_details() -> None:
    c = _app()
    r = c.get("/me")
    assert r.status_code == 401 and r.json()["code"] == "unauthenticated"
    assert r.headers["content-type"].startswith("application/problem+json")
    assert c.get("/me", headers={"Authorization": f"Bearer {token()}"}).json() == {"sub": "u1"}
    r = c.get("/staff", headers={"Authorization": f"Bearer {token()}"})
    assert r.status_code == 403 and r.json()["code"] == "forbidden"
    ok = token(roles=["nurse"], amr=["mfa"])
    assert c.get("/staff", headers={"Authorization": f"Bearer {ok}"}).status_code == 200


def test_unknown_role_in_require_roles_fails_fast() -> None:
    with pytest.raises(ValueError):
        Authenticator(SETTINGS, resolver).require_roles("root")


def test_from_env_reads_the_shared_mfa_switch(monkeypatch: pytest.MonkeyPatch) -> None:
    # same variable name as Java (shp.auth.require-mfa-for-staff): N3
    monkeypatch.setenv("OIDC_AUTHORITY", ISS)
    monkeypatch.setenv("OIDC_AUDIENCE", "svc-clinical")
    monkeypatch.setenv("SHP_REQUIRE_MFA_FOR_STAFF", "false")
    settings = AuthSettings.from_env()
    assert settings.require_mfa_for_staff is False
    assert settings.accepted_audiences() == ("svc-clinical", "shp-api")
    monkeypatch.delenv("SHP_REQUIRE_MFA_FOR_STAFF")
    assert AuthSettings.from_env().require_mfa_for_staff is True


def test_require_service_checks_azp_and_the_internal_role() -> None:
    auth = Authenticator(CLINICAL, resolver)
    app = FastAPI()
    install_error_handlers(app)

    @app.get("/internal/v1/patients/{pid}")
    def internal(
        pid: str, p: Annotated[Principal, Depends(auth.require_service("clinical", "svc-scheduling"))]
    ) -> dict[str, str]:
        return {"sub": p.subject, "pid": pid}

    c = TestClient(app)

    def call(**claims: Any) -> int:
        status: int = c.get(
            "/internal/v1/patients/patient-1",
            headers={"Authorization": f"Bearer {token(**claims)}"},
        ).status_code
        return status

    # Java parity: org.shp.security.ShpJwt.requireService
    assert call(aud=["svc-clinical"], azp="svc-scheduling", roles=["internal.clinical"]) == 200
    assert call(aud=["svc-clinical"], azp="svc-ai", roles=["internal.clinical"]) == 403
    assert call(aud=["svc-clinical"], azp="svc-scheduling", roles=["internal.document"]) == 403
    # a user token never passes an internal endpoint, even one whose audience contains shp-api
    assert call(aud=["shp-api"], roles=["doctor"], amr=["mfa"]) == 403
