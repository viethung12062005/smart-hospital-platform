#!/usr/bin/env python3
"""Sinh JSON Schema + ví dụ cho 11 event (docs/03-architecture/detailed-design/13-events.md) vào contracts/events/.
Dùng: python3 -I tools/contracts/gen_events.py   (ghi đè file sinh ra; sửa schema ở ĐÂY rồi chạy lại, không sửa tay)
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "contracts" / "events"
BASE = "https://shp.example.org/schemas"

UUID = {"type": "string", "format": "uuid"}
DT = {"type": "string", "format": "date-time"}
INT = {"type": "integer", "minimum": 1}
S = {"type": "string"}
NS = {"type": "string", "minLength": 1}


def enum(*v: str) -> dict:
    return {"type": "string", "enum": list(v)}


_U = ["0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e%02d" % i for i in range(1, 12)]
_T = "2026-12-03T01:15:00Z"
_T2 = "2026-12-03T01:30:00Z"
_N = "2026-11-20T02:15:00Z"

# (context, event, mô tả, {trường: (schema, bắt buộc)}, ví dụ)
EVENTS = [
    ("scheduling", "appointment-booked", "E1: đặt lịch thành công", {
        "appointmentId": (UUID, True), "version": (INT, True), "patientId": (UUID, True), "practitionerId": (UUID, True),
        "slotId": (UUID, True), "specialty": (NS, True), "start": (DT, True), "end": (DT, True),
        "channel": (enum("web", "chatbot", "staff"), True), "bookedAt": (DT, True),
        "ageBand": (S, False),
    }, {"appointmentId": _U[0], "version": 1, "patientId": _U[1], "practitionerId": _U[2], "slotId": _U[3], "specialty": "cardiology",
        "start": _T, "end": _T2, "channel": "web", "bookedAt": _N, "ageBand": "40-49"}),
    ("scheduling", "appointment-cancelled", "E2: hủy lịch", {
        "appointmentId": (UUID, True), "version": (INT, True), "patientId": (UUID, True), "practitionerId": (UUID, True),
        "start": (DT, True), "cancelReason": (NS, True), "cancelledBy": (enum("patient", "staff"), True),
        "cancelledAt": (DT, True), "hoursBeforeStart": ({"type": "number"}, True),
    }, {"appointmentId": _U[0], "version": 2, "patientId": _U[1], "practitionerId": _U[2], "start": _T, "cancelReason": "patient-request",
        "cancelledBy": "patient", "cancelledAt": _N, "hoursBeforeStart": 310.0}),
    ("scheduling", "appointment-rescheduled", "E3: đổi lịch (một event cho cặp hủy + đặt mới)", {
        "oldAppointmentId": (UUID, True), "newAppointmentId": (UUID, True), "version": (INT, True), "patientId": (UUID, True),
        "practitionerId": (UUID, True), "oldStart": (DT, True), "newStart": (DT, True), "newEnd": (DT, True), "specialty": (NS, True),
    }, {"oldAppointmentId": _U[0], "newAppointmentId": _U[4], "version": 1, "patientId": _U[1], "practitionerId": _U[2],
        "oldStart": _T, "newStart": "2026-12-04T01:15:00Z", "newEnd": "2026-12-04T01:30:00Z", "specialty": "cardiology"}),
    ("scheduling", "appointment-checked-in", "E4: check-in", {
        "appointmentId": (UUID, True), "version": (INT, True), "patientId": (UUID, True), "practitionerId": (UUID, True),
        "specialty": (NS, True), "checkedInAt": (DT, True),
    }, {"appointmentId": _U[0], "version": 3, "patientId": _U[1], "practitionerId": _U[2], "specialty": "cardiology", "checkedInAt": _T}),
    ("scheduling", "appointment-status-changed", "E5: no-show (job) hoặc fulfilled (sau E6)", {
        "appointmentId": (UUID, True), "version": (INT, True), "patientId": (UUID, True),
        "previousStatus": (NS, True), "newStatus": (enum("noshow", "fulfilled"), True), "changedAt": (DT, True),
    }, {"appointmentId": _U[0], "version": 4, "patientId": _U[1], "previousStatus": "arrived", "newStatus": "fulfilled", "changedAt": _T2}),
    ("clinical", "encounter-finished", "E6: kết thúc encounter", {
        "encounterId": (UUID, True), "appointmentId": (UUID, True), "patientId": (UUID, True), "practitionerId": (UUID, True),
        "finishedAt": (DT, True), "version": (INT, True),
    }, {"encounterId": _U[5], "appointmentId": _U[0], "patientId": _U[1], "practitionerId": _U[2], "finishedAt": _T2, "version": 3}),
    ("clinical", "patient-merged", "E7: gộp hồ sơ bệnh nhân", {
        "sourcePatientId": (UUID, True), "targetPatientId": (UUID, True), "mergedAt": (DT, True),
    }, {"sourcePatientId": _U[1], "targetPatientId": _U[6], "mergedAt": _N}),
    ("document", "document-uploaded", "E8: tài liệu quét sạch, sẵn sàng", {
        "documentId": (UUID, True), "version": (INT, True), "patientId": (UUID, True), "encounterId": (UUID, False),
        "type": (NS, True), "contentType": (NS, True), "size": ({"type": "integer", "minimum": 0}, True),
        "uploadedBy": (NS, True), "availableAt": (DT, True),
    }, {"documentId": _U[7], "version": 1, "patientId": _U[1], "encounterId": _U[5], "type": "11502-2", "contentType": "application/pdf",
        "size": 182044, "uploadedBy": "Practitioner/" + _U[2], "availableAt": _T2}),
    ("document", "document-released", "E9: phát hành tài liệu cho bệnh nhân", {
        "documentId": (UUID, True), "patientId": (UUID, True), "releasedAt": (DT, True), "type": (NS, True),
    }, {"documentId": _U[7], "patientId": _U[1], "releasedAt": _T2, "type": "11502-2"}),
    ("ml", "no-show-scored", "E10: điểm nguy cơ no-show", {
        "appointmentId": (UUID, True), "patientId": (UUID, True), "start": (DT, True),
        "score": ({"type": "number", "minimum": 0, "maximum": 1}, True), "threshold": ({"type": "number", "minimum": 0, "maximum": 1}, True),
        "highRisk": ({"type": "boolean"}, True), "modelName": (NS, True), "modelVersion": (NS, True), "scoredAt": (DT, True),
    }, {"appointmentId": _U[0], "patientId": _U[1], "start": _T, "score": 0.42, "threshold": 0.35, "highRisk": True,
        "modelName": "no-show", "modelVersion": "3", "scoredAt": _N}),
    ("audit", "recorded", "E11: bản ghi audit (không chứa nội dung lâm sàng)", {
        "eventId": (UUID, True), "occurredAt": (DT, True), "service": (NS, True),
        "action": (enum("read", "search", "create", "update", "delete", "download-url", "ai-query", "ai-summary",
                        "break-glass-start", "export", "login"), True),
        "outcome": (enum("success", "denied", "error"), True),
        "actor": ({"type": "object", "additionalProperties": False, "required": ["subject", "role"], "properties": {
            "subject": NS, "role": enum("patient", "doctor", "nurse", "admin", "dpo", "system"), "fhirUser": S}}, True),
        "patientId": ({"type": ["string", "null"], "format": "uuid"}, False),
        "resource": (NS, True),
        "purposeOfUse": (enum("TREAT", "ETREAT", "HOPERAT", "PATRQT"), False),
        "reason": ({"type": ["string", "null"]}, False),
        "traceId": (S, False),
        "client": ({"type": "object", "additionalProperties": False, "properties": {"ip": S, "userAgent": S}}, False),
    }, {"eventId": _U[10], "occurredAt": _N, "service": "clinical", "action": "read", "outcome": "success",
        "actor": {"subject": "kc-doctor-demo", "role": "doctor", "fhirUser": "Practitioner/" + _U[2]}, "patientId": _U[1],
        "resource": "Encounter/" + _U[5], "purposeOfUse": "TREAT", "reason": None,
        "traceId": "4bf92f3577b34da6a3ce929d0e0e4736", "client": {"ip": "10.1.2.x", "userAgent": "Mozilla/5.0"}}),
]

ENVELOPE = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$id": f"{BASE}/envelope.json",
    "title": "CloudEvents 1.0 envelope của SHP",
    "type": "object",
    "additionalProperties": False,
    "required": ["specversion", "id", "source", "type", "subject", "time", "datacontenttype", "data"],
    "properties": {
        "specversion": {"const": "1.0"},
        "id": UUID,
        "source": {"type": "string", "pattern": "^/services/[a-z-]+$"},
        "type": {"type": "string", "pattern": r"^shp\.[a-z]+\.[a-z-]+\.v[0-9]+$"},
        "subject": {"type": "string", "pattern": r"^[A-Za-z]+/[0-9a-f-]{36}$"},
        "time": DT,
        "datacontenttype": {"const": "application/json"},
        "dataschema": {"type": "string", "format": "uri"},
        "traceparent": {"type": "string", "pattern": r"^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"},
        "data": {"type": "object"},
    },
}


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "envelope.json").write_text(json.dumps(ENVELOPE, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    for ctx, name, desc, fields, example in EVENTS:
        d = OUT / ctx
        d.mkdir(exist_ok=True)
        schema = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$id": f"{BASE}/{ctx}/{name}/v1.json",
            "title": f"shp.{ctx}.{name}.v1",
            "description": desc + ". Chỉ ID/mã/thời điểm/trạng thái, không PII. Chỉ được thêm trường tùy chọn trong cùng version.",
            "type": "object",
            "required": [k for k, (_, req) in fields.items() if req],
            "properties": {k: v for k, (v, _) in fields.items()},
        }
        (d / f"{name}.v1.json").write_text(json.dumps(schema, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        (d / f"{name}.v1.example.json").write_text(json.dumps(example, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {len(EVENTS)} events + envelope to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
