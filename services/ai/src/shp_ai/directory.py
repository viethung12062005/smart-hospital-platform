"""Look up the caller's department (specialty) and care relationships via the clinical internal API (09 §4), cached
for 5 minutes.

sub → /internal/v1/identity/{sub} → Practitioner/<id> → /internal/v1/practitioners/{id}/roles → specialty.
Called with the ai service token (client credentials). A clinical error → 503; never guess permissions.
"""

import time
from collections.abc import Callable
from typing import Any, Protocol

import httpx

from shp_platform.errors import ShpError
from shp_platform.security import Principal

CACHE_SECONDS = 300


class TokenSource(Protocol):
    def token(self) -> str: ...


class Directory(Protocol):
    def departments(self, principal: Principal) -> tuple[str, ...]: ...

    def fhir_user(self, principal: Principal) -> str | None: ...

    def has_care(self, principal: Principal, patient_id: str) -> bool: ...

    def care_via(self, principal: Principal, patient_id: str) -> str | None: ...


class ClinicalDirectory:
    def __init__(
        self,
        base_url: str,
        tokens: TokenSource,
        http: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._base = base_url.rstrip("/")
        self._tokens = tokens
        self._http = http or httpx.Client(timeout=5.0)
        self._clock = clock
        self._cache: dict[str, tuple[float, Any]] = {}

    def _get(self, path: str, cache: bool = True) -> Any:
        now = self._clock()
        hit = self._cache.get(path)
        if cache and hit and hit[0] > now:
            return hit[1]
        try:
            res = self._http.get(self._base + path, headers={"Authorization": f"Bearer {self._tokens.token()}"})
        except httpx.HTTPError as exc:
            raise ShpError("dependency-unavailable") from exc
        if res.status_code == 404:
            value = None
        elif res.status_code == 200:
            value = res.json()
        else:
            raise ShpError("dependency-unavailable")
        if cache:
            self._cache[path] = (now + CACHE_SECONDS, value)
        return value

    def fhir_user(self, principal: Principal) -> str | None:
        ident = self._get(f"/internal/v1/identity/{principal.subject}")
        return ident.get("fhirUser") if ident and not ident.get("disabled") else None

    def departments(self, principal: Principal) -> tuple[str, ...]:
        claimed = principal.claims.get("shp_departments")
        if isinstance(claimed, list) and all(isinstance(d, str) for d in claimed):
            return tuple(claimed)  # the Keycloak mapper may already set it; otherwise look it up in clinical
        fu = self.fhir_user(principal)
        if not fu or not fu.startswith("Practitioner/"):
            return ()
        roles = self._get(f"/internal/v1/practitioners/{fu.split('/', 1)[1]}/roles") or []
        return tuple(sorted({r["specialty"] for r in roles if r.get("active", True) and r.get("specialty")}))

    def has_care(self, principal: Principal, patient_id: str) -> bool:
        fu = self.fhir_user(principal)
        if not fu or not fu.startswith("Practitioner/"):
            return False
        res = self._get(
            f"/internal/v1/care-relationships/check?practitioner={fu.split('/', 1)[1]}&patient={patient_id}",
            cache=False,
        )
        return bool(res and res.get("care"))

    def care_via(self, principal: Principal, patient_id: str) -> str | None:
        """appointment | encounter | break-glass, or None when there is no care relationship."""
        fu = self.fhir_user(principal)
        if not fu or not fu.startswith("Practitioner/"):
            return None
        res = self._get(
            f"/internal/v1/care-relationships/check?practitioner={fu.split('/', 1)[1]}&patient={patient_id}",
            cache=False,
        )
        return str(res.get("via") or "appointment") if res and res.get("care") else None


class StaticDirectory:
    """Fake for test/dev: departments by subject, care relationships by (subject, patient)."""

    def __init__(
        self,
        departments: dict[str, tuple[str, ...]] | None = None,
        care: set[tuple[str, str]] | None = None,
        break_glass: set[tuple[str, str]] | None = None,
    ) -> None:
        self._deps = departments or {}
        self._care = care or set()
        self._bg = break_glass or set()

    def departments(self, principal: Principal) -> tuple[str, ...]:
        return self._deps.get(principal.subject, ())

    def fhir_user(self, principal: Principal) -> str | None:
        return f"Practitioner/{principal.subject}"

    def has_care(self, principal: Principal, patient_id: str) -> bool:
        return (principal.subject, patient_id) in self._care

    def care_via(self, principal: Principal, patient_id: str) -> str | None:
        if (principal.subject, patient_id) in self._bg:
            return "break-glass"
        return "appointment" if (principal.subject, patient_id) in self._care else None
