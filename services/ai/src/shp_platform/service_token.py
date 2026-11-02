"""Token dịch vụ (OAuth2 client credentials, Keycloak) để gọi `/internal/*` của service khác (15 §7, IAM-02).

Cache tới trước khi hết hạn 30 giây. Secret client lấy từ Key Vault/secret Kubernetes; không bao giờ log token.
"""

import threading
import time
from collections.abc import Callable

import httpx

from shp_platform.errors import ShpError


class ServiceTokenProvider:
    def __init__(
        self,
        token_endpoint: str,
        client_id: str,
        client_secret: str,
        http: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._endpoint = token_endpoint
        self._auth = (client_id, client_secret)
        self._http = http or httpx.Client(timeout=5.0)
        self._clock = clock
        self._lock = threading.Lock()
        self._token: tuple[str, float] | None = None

    def token(self) -> str:
        with self._lock:
            now = self._clock()
            if self._token and self._token[1] > now:
                return self._token[0]
            try:
                res = self._http.post(self._endpoint, data={"grant_type": "client_credentials"}, auth=self._auth)
            except httpx.HTTPError as exc:
                raise ShpError("dependency-unavailable") from exc
            if res.status_code != 200:
                raise ShpError("dependency-unavailable")
            body = res.json()
            token = body.get("access_token")
            if not isinstance(token, str) or not token:
                raise ShpError("dependency-unavailable")
            self._token = (token, now + max(int(body.get("expires_in", 60)) - 30, 0))
            return token


class StaticToken:
    """Bản giả cho dev/test."""

    def __init__(self, value: str = "test-service-token") -> None:  # noqa: S107 - giá trị giả cho test
        self.value = value

    def token(self) -> str:
        return self.value
