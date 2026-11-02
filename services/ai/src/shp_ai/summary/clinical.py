"""Thu thập dữ liệu cho tóm tắt hồ sơ bằng token của bác sĩ (AI-19; 12 §3, 15 §7).

Token người dùng được đổi (RFC 8693) sang audience `clinical`, nên clinical áp nguyên ma trận quyền cho bác sĩ đó
(Care/break-glass). AI không có quyền nào rộng hơn người gọi. Lỗi quyền của clinical được trả nguyên mã.
"""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import date
from typing import Any

import httpx

from shp_platform.errors import ShpError

ABNORMAL = {"H", "HH", "L", "LL", "A", "AA", "HU", "LU"}
MAX_TIMELINE = 100


@dataclass(frozen=True)
class Evidence:
    key: str  # E1..En
    ref: str  # Condition/<id> ...
    kind: str  # condition | allergy | encounter | observation
    time: str
    text: str
    abnormal: bool = False


class ClinicalReader:
    def __init__(self, base_url: str, user_token: Callable[[], str], http: httpx.Client | None = None) -> None:
        self._base = base_url.rstrip("/")
        self._token = user_token
        self._http = http or httpx.Client(timeout=5.0)
        self.practitioner_names: list[str] = []  # che trước khi gửi LLM ngoài Azure

    def _get(self, path: str, params: dict[str, Any]) -> dict[str, Any]:
        try:
            res = self._http.get(self._base + path, params=params, headers={"Authorization": f"Bearer {self._token()}"})
        except httpx.HTTPError as exc:
            raise ShpError("dependency-unavailable") from exc
        if res.status_code in (401, 403, 404):
            code = "forbidden"
            try:
                body = res.json()
                code = body.get("code") or body["issue"][0]["details"]["coding"][0]["code"]
            except (ValueError, KeyError, IndexError, TypeError):
                pass
            raise ShpError(
                code if code in ("no-care-relationship", "break-glass-required", "not-found") else "forbidden"
            )
        if res.status_code != 200:
            raise ShpError("dependency-unavailable")
        out: dict[str, Any] = res.json()
        return out

    def _bundle(self, resource: str, params: dict[str, Any]) -> list[dict[str, Any]]:
        b = self._get(f"/fhir/{resource}", {**params, "_count": 50})
        return [e["resource"] for e in b.get("entry", []) if "resource" in e]

    def gather(self, patient_id: str, since: date) -> list[Evidence]:
        conditions = self._bundle("Condition", {"patient": patient_id})
        allergies = self._bundle("AllergyIntolerance", {"patient": patient_id})
        observations = self._bundle("Observation", {"patient": patient_id, "date": f"ge{since.isoformat()}"})
        timeline: list[dict[str, Any]] = []
        cursor: str | None = None
        while len(timeline) < MAX_TIMELINE:
            params: dict[str, Any] = {"types": "encounter", "from": since.isoformat(), "limit": 50}
            if cursor:
                params["cursor"] = cursor
            page = self._get(f"/api/v1/clinical/patients/{patient_id}/timeline", params)
            timeline += page.get("items", [])
            cursor = page.get("nextCursor")
            if not cursor:
                break
        ev: list[Evidence] = []

        def add(ref: str, kind: str, time: str, text: str, abnormal: bool = False) -> None:
            ev.append(Evidence(f"E{len(ev) + 1}", ref, kind, time, text.strip(), abnormal))

        for c in conditions:
            code = (c.get("code", {}).get("coding") or [{}])[0]
            status = (c.get("clinicalStatus", {}).get("coding") or [{}])[0].get("code", "")
            add(
                f"Condition/{c['id']}",
                "condition",
                str(c.get("onsetDateTime") or c.get("recordedDate") or ""),
                f"{code.get('display', c.get('code', {}).get('text', ''))} ({code.get('code', '')}) {status}",
            )
        for a in allergies:
            sub = (a.get("code", {}).get("coding") or [{}])[0].get("display") or a.get("code", {}).get("text", "")
            add(
                f"AllergyIntolerance/{a['id']}",
                "allergy",
                str(a.get("recordedDate", "")),
                f"Dị ứng {sub}, mức độ {a.get('criticality', 'không rõ')}",
            )
        for item in timeline[:MAX_TIMELINE]:
            if (item.get("practitioner") or {}).get("display"):
                self.practitioner_names.append(item["practitioner"]["display"])
            add(item["ref"], "encounter", item.get("time", ""), f"{item.get('title', '')}: {item.get('summary', '')}")
        for o in observations:
            interp = {c.get("code") for i in o.get("interpretation", []) for c in i.get("coding", [])}
            q = o.get("valueQuantity", {})
            name = (o.get("code", {}).get("coding") or [{}])[0].get("display") or o.get("code", {}).get("text", "")
            value = f"{q.get('value', '')} {q.get('unit', '')}".strip() or str(o.get("valueString", ""))
            flag = " (bất thường)" if interp & ABNORMAL else ""
            add(
                f"Observation/{o['id']}",
                "observation",
                str(o.get("effectiveDateTime", "")),
                f"{name} {value}{flag}",
                bool(interp & ABNORMAL),
            )
        return ev
