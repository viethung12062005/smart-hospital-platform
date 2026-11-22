"""Service token (OAuth2 client credentials, Keycloak) for calling another service's `/internal/*` (15 §7, IAM-02).

Cached until 30 seconds before expiry. The client secret comes from Key Vault/Kubernetes secrets; never log the token.
"""

import re
import threading
import time
from collections.abc import Callable

import httpx

from shp_platform.client_auth import ClientAuth
from shp_platform.errors import ShpError


class ServiceTokenProvider:
    def __init__(
        self,
        token_endpoint: str,
        client_id: str,
        client_secret: str,
        http: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
        assertion_file: str = "",
    ) -> None:
        self._endpoint = token_endpoint
        self._auth = ClientAuth(client_id, client_secret, assertion_file)
        self._http = http or httpx.Client(timeout=5.0)
        self._clock = clock
        self._lock = threading.Lock()
        self._tokens: dict[str, tuple[str, float]] = {}

    def token(self, target: str) -> str:
        if not re.fullmatch(r"[a-z][a-z0-9-]*", target):
            raise ValueError("Expected one target service name")
        with self._lock:
            now = self._clock()
            cached = self._tokens.get(target)
            if cached and cached[1] > now:
                return cached[0]
            try:
                form, basic = self._auth.apply({"grant_type": "client_credentials", "scope": f"call-{target}"})
                res = self._http.post(self._endpoint, data=form, auth=basic or httpx.USE_CLIENT_DEFAULT)
            except (httpx.HTTPError, OSError) as exc:
                raise ShpError("dependency-unavailable") from exc
            if res.status_code != 200:
                raise ShpError("dependency-unavailable")
            body = res.json()
            token = body.get("access_token")
            if not isinstance(token, str) or not token:
                raise ShpError("dependency-unavailable")
            self._tokens[target] = (token, now + max(int(body.get("expires_in", 60)) - 30, 0))
            return token


class StaticToken:
    """Fake implementation for dev/test."""

    def __init__(self, value: str = "test-service-token") -> None:  # noqa: S107 - fake value for tests
        self.value = value

    def token(self, target: str) -> str:
        return self.value
