"""Token exchange on behalf of the user (RFC 8693, Keycloak) for ai calling clinical/scheduling (PY-02, 15 §7).

The resulting token keeps the user's `sub`, so the authorization matrix applies unchanged; `audience` is the target
service.
Results are cached by (original token, audience) until 30 seconds before expiry. Never log tokens.
"""

import hashlib
import time
from collections.abc import Callable
from dataclasses import dataclass

import httpx

from shp_platform.errors import ShpError

GRANT = "urn:ietf:params:oauth:grant-type:token-exchange"
ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"  # noqa: S105 - token type name, not a secret


@dataclass(frozen=True)
class ExchangeSettings:
    token_endpoint: str  # <issuer>/protocol/openid-connect/token
    client_id: str
    client_secret: str  # from Key Vault/Kubernetes secrets, never in the repo


class TokenExchanger:
    def __init__(
        self,
        settings: ExchangeSettings,
        http: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._settings = settings
        self._http = http or httpx.Client(timeout=5.0)
        self._clock = clock
        self._cache: dict[tuple[str, str], tuple[str, float]] = {}

    def exchange(self, subject_token: str, audience: str) -> str:
        key = (hashlib.sha256(subject_token.encode()).hexdigest(), audience)
        hit = self._cache.get(key)
        now = self._clock()
        if hit and hit[1] > now:
            return hit[0]
        try:
            res = self._http.post(
                self._settings.token_endpoint,
                data={
                    "grant_type": GRANT,
                    "subject_token": subject_token,
                    "subject_token_type": ACCESS_TOKEN_TYPE,
                    "requested_token_type": ACCESS_TOKEN_TYPE,
                    "audience": audience,
                },
                auth=(self._settings.client_id, self._settings.client_secret),
            )
        except httpx.HTTPError as exc:
            raise ShpError("dependency-unavailable") from exc
        if res.status_code in (400, 401, 403):
            # The user token expired/was revoked, or the client may not exchange to this audience.
            raise ShpError("forbidden")
        if res.status_code != 200:
            raise ShpError("dependency-unavailable")
        body = res.json()
        token = body.get("access_token")
        if not isinstance(token, str) or not token:
            raise ShpError("dependency-unavailable")
        ttl = int(body.get("expires_in", 60))
        self._cache[key] = (token, now + max(ttl - 30, 0))
        if len(self._cache) > 10_000:
            self._cache = {k: v for k, v in self._cache.items() if v[1] > now}
        return token
