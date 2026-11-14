"""Keycloak JWT authentication for FastAPI (PY-01). Rules match org.shp.security.ShpJwt.

- Verify the signature (JWKS), token expiry (allowed clock skew), issuer, audience.
- `roles` claim: keep only valid roles, case-insensitive.
- Staff (doctor, nurse, admin, dpo) must have `amr` containing "mfa" (NFR-SEC1); can be disabled locally.
Tokens are never logged.
"""

import os
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from typing import Annotated, Any

import jwt
from fastapi import Depends, Request

from shp_platform.errors import ShpError

ALL_ROLES = frozenset({"patient", "doctor", "nurse", "admin", "dpo"})
STAFF_ROLES = frozenset({"doctor", "nurse", "admin", "dpo"})
ALGORITHMS = ["RS256", "PS256", "ES256"]


@dataclass(frozen=True)
class AuthSettings:
    """authority = Keycloak issuer (cloud: https://<domain>/idp/realms/shp); reads env like the Java version."""

    authority: str
    audience: str
    require_mfa_for_staff: bool = True
    clock_skew_seconds: int = 30
    jwks_uri: str = ""

    @staticmethod
    def from_env() -> "AuthSettings":
        return AuthSettings(
            authority=os.environ.get("OIDC_AUTHORITY", ""),
            audience=os.environ.get("OIDC_AUDIENCE", ""),
            require_mfa_for_staff=os.environ.get("SHP_AUTH_REQUIRE_MFA_FOR_STAFF", "true").lower() != "false",
            clock_skew_seconds=int(os.environ.get("SHP_AUTH_CLOCK_SKEW_SECONDS", "30")),
            jwks_uri=os.environ.get("SHP_AUTH_JWK_SET_URI", ""),
        )

    def resolved_jwks_uri(self) -> str:
        # Keycloak: <issuer>/protocol/openid-connect/certs
        return self.jwks_uri or self.authority.rstrip("/") + "/protocol/openid-connect/certs"


@dataclass(frozen=True)
class Principal:
    subject: str
    roles: frozenset[str]
    token: str = field(repr=False)  # needed for token exchange; repr=False so it never leaks into logs
    claims: dict[str, Any] = field(repr=False, default_factory=dict)

    @property
    def is_staff(self) -> bool:
        return bool(self.roles & STAFF_ROLES)

    def has_any(self, roles: Iterable[str]) -> bool:
        return bool(self.roles & frozenset(roles))


KeyResolver = Callable[[str], Any]  # token -> public key for signature verification


def jwks_key_resolver(settings: AuthSettings) -> KeyResolver:
    client = jwt.PyJWKClient(settings.resolved_jwks_uri(), cache_keys=True, lifespan=300)

    def resolve(token: str) -> Any:
        return client.get_signing_key_from_jwt(token).key

    return resolve


def roles_of(claims: dict[str, Any]) -> frozenset[str]:
    raw = claims.get("roles")
    if not isinstance(raw, list):
        return frozenset()
    return frozenset(r.lower() for r in raw if isinstance(r, str) and r.lower() in ALL_ROLES)


def verify_token(token: str, settings: AuthSettings, key_resolver: KeyResolver) -> Principal:
    """Return a Principal or raise ShpError("unauthenticated"). Do not distinguish the reason to the client (one
    error code only)."""
    if not settings.authority or not settings.audience:
        raise ShpError("unauthenticated")
    try:
        claims: dict[str, Any] = jwt.decode(
            token,
            key=key_resolver(token),
            algorithms=ALGORITHMS,
            audience=settings.audience,
            issuer=settings.authority,
            leeway=settings.clock_skew_seconds,
            options={"require": ["exp", "iat", "iss", "sub"]},
        )
    except (jwt.PyJWTError, ValueError) as exc:
        raise ShpError("unauthenticated") from exc
    roles = roles_of(claims)
    if settings.require_mfa_for_staff and roles & STAFF_ROLES:
        amr = claims.get("amr")
        if not (isinstance(amr, list) and any(isinstance(a, str) and a.lower() == "mfa" for a in amr)):
            raise ShpError("unauthenticated", "mfa-required")
    return Principal(subject=str(claims["sub"]), roles=roles, token=token, claims=claims)


class Authenticator:
    """Dependency FastAPI: `principal: Annotated[Principal, Depends(auth.principal)]`."""

    def __init__(self, settings: AuthSettings, key_resolver: KeyResolver | None = None) -> None:
        self.settings = settings
        self._resolver = key_resolver

    def _key_resolver(self) -> KeyResolver:
        if self._resolver is None:
            self._resolver = jwks_key_resolver(self.settings)
        return self._resolver

    def principal(self, request: Request) -> Principal:
        header = request.headers.get("authorization", "")
        scheme, _, token = header.partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            raise ShpError("unauthenticated")
        return verify_token(token.strip(), self.settings, self._key_resolver())

    def require_roles(self, *roles: str) -> Callable[..., Principal]:
        unknown = set(roles) - ALL_ROLES
        if unknown:
            raise ValueError(f"invalid role: {sorted(unknown)}")

        def dependency(principal: Annotated[Principal, Depends(self.principal)]) -> Principal:
            if not principal.has_any(roles):
                raise ShpError("forbidden")
            return principal

        return dependency

    def require_service(self, service: str, *callers: str) -> Callable[..., Principal]:
        """`/internal/*` endpoint (15 §7): a service token (client credentials) with role `internal.<service>` and `azp`
        in the list of allowed callers. The gateway always blocks `/internal/**` from outside."""
        allowed = frozenset(callers)

        def dependency(principal: Annotated[Principal, Depends(self.principal)]) -> Principal:
            raw = principal.claims.get("roles")
            roles = raw if isinstance(raw, list) else []
            if principal.claims.get("azp") not in allowed or f"internal.{service}" not in roles:
                raise ShpError("forbidden")
            return principal

        return dependency
