"""The booking bot's 4 tools, with parameters validated by schema (AI-29; 12 §5). Exactly matches the `specialty`
value set in contracts/terminology/code-systems.yaml (copied by hand here because the service build context does not
include the repo root).
"""

import re
from datetime import date, timedelta
from typing import Any

SPECIALTIES = (
    "internal-medicine",
    "cardiology",
    "pediatrics",
    "obstetrics-gynecology",
    "dermatology",
    "ent",
    "ophthalmology",
    "orthopedics",
    "neurology",
    "gastroenterology",
    "endocrinology",
    "general-surgery",
)
MAX_RANGE_DAYS = 31
_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.IGNORECASE)

SEARCH_SLOTS = "search_slots"
LIST_MY_APPOINTMENTS = "list_my_appointments"
PROPOSE_BOOKING = "propose_booking"
PROPOSE_CANCEL = "propose_cancel"
ALLOWED_TOOLS = frozenset({SEARCH_SLOTS, LIST_MY_APPOINTMENTS, PROPOSE_BOOKING, PROPOSE_CANCEL})
READ_TOOLS = frozenset({SEARCH_SLOTS, LIST_MY_APPOINTMENTS})  # run automatically, no confirmation needed
PROPOSE_TOOLS = frozenset({PROPOSE_BOOKING, PROPOSE_CANCEL})  # only create a pending_action, never execute

TOOL_SCHEMAS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": SEARCH_SLOTS,
            "description": "Tìm slot còn trống theo chuyên khoa và khoảng ngày.",
            "parameters": {
                "type": "object",
                "additionalProperties": False,
                "required": ["specialty", "from", "to"],
                "properties": {
                    "specialty": {"type": "string", "enum": list(SPECIALTIES)},
                    "from": {"type": "string", "format": "date"},
                    "to": {"type": "string", "format": "date"},
                    "practitionerId": {"type": "string"},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": LIST_MY_APPOINTMENTS,
            "description": "Xem lịch hẹn đang có của chính người dùng.",
            "parameters": {"type": "object", "additionalProperties": False, "properties": {}},
        },
    },
    {
        "type": "function",
        "function": {
            "name": PROPOSE_BOOKING,
            "description": "Đề xuất đặt một slot. KHÔNG đặt ngay — chỉ tạo đề xuất để người dùng xác nhận "
            "trên giao diện.",
            "parameters": {
                "type": "object",
                "additionalProperties": False,
                "required": ["slotId"],
                "properties": {"slotId": {"type": "string"}, "comment": {"type": "string", "maxLength": 500}},
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": PROPOSE_CANCEL,
            "description": "Đề xuất hủy một lịch hẹn. KHÔNG hủy ngay — chỉ tạo đề xuất để người dùng xác nhận.",
            "parameters": {
                "type": "object",
                "additionalProperties": False,
                "required": ["appointmentId"],
                "properties": {"appointmentId": {"type": "string"}},
            },
        },
    },
]


class ToolValidationError(ValueError):
    pass


def validate_args(name: str, args: dict[str, Any]) -> dict[str, Any]:
    if name not in ALLOWED_TOOLS:
        raise ToolValidationError(f"tool không được phép: {name}")
    if name == SEARCH_SLOTS:
        specialty, from_, to = args.get("specialty"), args.get("from"), args.get("to")
        if specialty not in SPECIALTIES:
            raise ToolValidationError("specialty không hợp lệ")
        if not isinstance(from_, str) or not isinstance(to, str):
            raise ToolValidationError("from/to phải là ngày YYYY-MM-DD")
        try:
            start, end = date.fromisoformat(from_), date.fromisoformat(to)
        except ValueError as exc:
            raise ToolValidationError("from/to phải là ngày YYYY-MM-DD") from exc
        if end <= start or (end - start) > timedelta(days=MAX_RANGE_DAYS) or start < date.today():
            raise ToolValidationError("khoảng ngày không hợp lệ (tối đa 31 ngày, không ở quá khứ)")
        pid = args.get("practitionerId")
        if pid is not None and not _UUID.match(str(pid)):
            raise ToolValidationError("practitionerId không hợp lệ")
        return {"specialty": specialty, "from": start.isoformat(), "to": end.isoformat(), "practitionerId": pid}
    if name == LIST_MY_APPOINTMENTS:
        return {}
    if name == PROPOSE_BOOKING:
        slot_id = args.get("slotId")
        if not isinstance(slot_id, str) or not _UUID.match(slot_id):
            raise ToolValidationError("slotId không hợp lệ")
        comment = args.get("comment")
        if comment is not None and (not isinstance(comment, str) or len(comment) > 500):
            raise ToolValidationError("comment không hợp lệ")
        return {"slotId": slot_id, "comment": comment}
    appointment_id = args.get("appointmentId")
    if not isinstance(appointment_id, str) or not _UUID.match(appointment_id):
        raise ToolValidationError("appointmentId không hợp lệ")
    return {"appointmentId": appointment_id}
