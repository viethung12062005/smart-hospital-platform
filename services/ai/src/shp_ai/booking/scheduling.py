"""Client for scheduling's public API using the patient's own token (AI-29; 12 §5).

The bot has no service token of its own: every call reuses the user's `Authorization` unchanged, so
scheduling enforces the patient's permissions (see free slots only, book/cancel only for themselves or a linked record).
"""

import uuid
from collections.abc import Callable
from datetime import date
from typing import Any

import httpx

from shp_platform.errors import ShpError


class SlotConflictError(ShpError):
    """409 slot-unavailable with the 3 nearest suggested slots (S11) — the bot proposes again instead of just
    reporting an error."""

    def __init__(self, suggestions: list[dict[str, Any]]) -> None:
        self.suggestions = suggestions
        super().__init__("slot-unavailable")


class SchedulingClient:
    def __init__(self, base_url: str, token: Callable[[], str], http: httpx.Client | None = None) -> None:
        self._base = base_url.rstrip("/")
        self._token = token
        self._http = http or httpx.Client(timeout=8.0)

    def _headers(self, idempotency_key: str | None = None, if_match: str | None = None) -> dict[str, str]:
        h = {"Authorization": f"Bearer {self._token()}"}
        if idempotency_key:
            h["Idempotency-Key"] = idempotency_key
        if if_match:
            h["If-Match"] = if_match
        return h

    def _call(self, method: str, path: str, **kw: Any) -> httpx.Response:
        try:
            res = self._http.request(method, self._base + path, **kw)
        except httpx.HTTPError as exc:
            raise ShpError("dependency-unavailable") from exc
        return res

    @staticmethod
    def _problem_code(res: httpx.Response) -> str:
        try:
            body = res.json()
            code = body.get("code") or body["issue"][0]["details"]["coding"][0]["code"]
            return str(code)
        except (ValueError, KeyError, IndexError, TypeError):
            return "forbidden" if res.status_code == 403 else "validation-failed"

    # ---------------- S8: find slots ----------------
    def search_slots(
        self, specialty: str, start: date, end: date, practitioner_id: str | None = None, limit: int = 10
    ) -> list[dict[str, Any]]:
        params: dict[str, Any] = {
            "specialty": specialty,
            "status": "free",
            "start": [f"ge{start.isoformat()}", f"lt{end.isoformat()}"],
            "_count": limit,
        }
        if practitioner_id:
            params["practitioner"] = f"Practitioner/{practitioner_id}"
        res = self._call("GET", "/fhir/Slot", params=params, headers=self._headers())
        if res.status_code != 200:
            raise ShpError(self._problem_code(res))
        bundle = res.json()
        return [e["resource"] for e in bundle.get("entry", [])]

    # ---------------- S13: my own appointments ----------------
    def my_appointments(self, patient_id: str, status: str = "booked") -> list[dict[str, Any]]:
        res = self._call(
            "GET",
            "/fhir/Appointment",
            params={"patient": f"Patient/{patient_id}", "status": status, "_sort": "date"},
            headers=self._headers(),
        )
        if res.status_code != 200:
            raise ShpError(self._problem_code(res))
        return [e["resource"] for e in res.json().get("entry", [])]

    # ---------------- S11: book (called only after the user confirms, AI8) ----------------
    def book(self, slot_id: str, patient_id: str, comment: str | None, idempotency_key: str) -> dict[str, Any]:
        parameters: list[dict[str, Any]] = [
            {"name": "slot", "valueReference": {"reference": f"Slot/{slot_id}"}},
            {"name": "patient", "valueReference": {"reference": f"Patient/{patient_id}"}},
            {"name": "channel", "valueCode": "chatbot"},
        ]
        if comment:
            parameters.append({"name": "comment", "valueString": comment})
        body = {"resourceType": "Parameters", "parameter": parameters}
        res = self._call(
            "POST", "/fhir/Appointment/$book", json=body, headers=self._headers(idempotency_key=idempotency_key)
        )
        if res.status_code == 409:
            # S11: a 409 on $book always means slot-unavailable; OperationOutcome carries extension[suggested-slots]
            info = res.json() if res.content else {}
            suggestions = (info.get("issue", [{}])[0].get("extension", {}) or {}).get("suggested-slots") or []
            raise SlotConflictError(suggestions)
        if res.status_code not in (200, 201):
            raise ShpError(self._problem_code(res))
        return dict(res.json())

    # ---------------- S14: cancel appointment ----------------
    def cancel(self, appointment_id: str, etag: str, idempotency_key: str) -> dict[str, Any]:
        body = {"resourceType": "Parameters", "parameter": [{"name": "reason", "valueCode": "patient-request"}]}
        res = self._call(
            "POST",
            f"/fhir/Appointment/{appointment_id}/$cancel",
            json=body,
            headers=self._headers(idempotency_key=idempotency_key, if_match=etag),
        )
        if res.status_code != 200:
            raise ShpError(self._problem_code(res))
        return dict(res.json())


def new_idempotency_key() -> str:
    return str(uuid.uuid4())
