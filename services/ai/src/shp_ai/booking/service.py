"""The booking bot's tool loop (AI-29, AI-31; 12 §5, AC-14.1, AC-14.2).

- At most 6 tool calls per message, 10 seconds total (AC-14.2 loop limit).
- `search_slots`/`list_my_appointments` run immediately. `propose_booking`/`propose_cancel` do NOT execute: they
  only create
  `pending_actions` (TTL 5 minutes) and end the turn — the real action runs only at AI8 after user confirmation.
- Any model failure (the router cannot answer) → `BookingUnavailableError` so the API returns 503 with a fallback
  form (AC-14.3).
- The bot only sees the caller's own `patientId`; there is no tool to read clinical records (AC-14.2).
"""

import json
import time
from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Any

from shp_ai import prompts
from shp_ai.assistant.guardrails import check_input
from shp_ai.booking.scheduling import SchedulingClient
from shp_ai.booking.tools import (
    LIST_MY_APPOINTMENTS,
    PROPOSE_BOOKING,
    SEARCH_SLOTS,
    TOOL_SCHEMAS,
    ToolValidationError,
    validate_args,
)
from shp_platform.errors import ShpError
from shp_platform.llm import ChatMessage, Router
from shp_platform.llm.router import RouterResult

TASK = "booking-agent"
MAX_ROUNDS = 6
TOTAL_TIMEOUT_SECONDS = 10.0
DEFAULT_REPLY_NO_CONVERGENCE = (
    "Mình chưa chắc ý bạn, bạn có thể nói rõ chuyên khoa và thời gian muốn khám không? "
    "Hoặc vào trang đặt lịch để chọn trực tiếp."
)


class BookingUnavailableError(Exception):
    """Every model in the chain failed (AC-14.3): the web falls back to the regular booking form."""


@dataclass
class Option:
    slot_id: str
    label: str


@dataclass
class PendingProposal:
    tool: str  # book_appointment | cancel_appointment
    arguments: dict[str, Any]
    summary: str


@dataclass
class TurnResult:
    reply: str
    options: list[Option] = field(default_factory=list)
    proposal: PendingProposal | None = None
    rounds_used: int = 0
    attempts: list[RouterResult] = field(default_factory=list)


def format_slot(slot: dict[str, Any]) -> Option:
    # A Slot (S8) does not include the doctor's name (only a schedule reference); looking up the Practitioner
    # needs an extra call to clinical (C9) — left for later work (the web can display more via slotId if needed).
    # The label currently has only date and time.
    start = slot.get("start", "")
    try:
        dt = datetime.fromisoformat(start.replace("Z", "+00:00"))
        label_time = dt.strftime("%d/%m lúc %H:%M")
    except ValueError:
        label_time = start
    return Option(slot_id=slot.get("id", ""), label=label_time)


def _tool_result_message(call_id: str, payload: Any) -> ChatMessage:
    return ChatMessage("tool", json.dumps(payload, ensure_ascii=False), tool_call_id=call_id)


def _assistant_tool_call_message(text: str, tool_calls: Any) -> ChatMessage:
    return ChatMessage("assistant", text or "", tool_calls=tool_calls)


def run_turn(
    router: Router,
    scheduling: SchedulingClient,
    patient_id: str,
    history: list[ChatMessage],
    message: str,
) -> TurnResult:
    inp = check_input(message)
    if not inp.ok:
        return TurnResult("Mình chỉ hỗ trợ đặt lịch khám; bạn hỏi lại theo hướng đó giúp mình nhé.")
    _, system = prompts.load(TASK)
    messages = [ChatMessage("system", system), *history, ChatMessage("user", message)]
    deadline = time.monotonic() + TOTAL_TIMEOUT_SECONDS
    options: list[Option] = []
    attempts: list[RouterResult] = []
    for round_no in range(1, MAX_ROUNDS + 1):
        if time.monotonic() >= deadline:
            return TurnResult(DEFAULT_REPLY_NO_CONVERGENCE, options, rounds_used=round_no - 1, attempts=attempts)
        result = router.complete(TASK, messages, tools=TOOL_SCHEMAS)
        attempts.append(result)
        if not result.answered:
            raise BookingUnavailableError
        if not result.tool_calls:
            return TurnResult(
                result.text or DEFAULT_REPLY_NO_CONVERGENCE, options, rounds_used=round_no, attempts=attempts
            )
        messages.append(_assistant_tool_call_message(result.text, result.tool_calls))
        for call in result.tool_calls:
            try:
                raw = json.loads(call.arguments) if call.arguments else {}
                args = validate_args(call.name, raw if isinstance(raw, dict) else {})
            except (ToolValidationError, json.JSONDecodeError) as exc:
                messages.append(_tool_result_message(call.id, {"error": str(exc)}))
                continue
            if call.name == SEARCH_SLOTS:
                try:
                    slots = scheduling.search_slots(
                        args["specialty"],
                        date.fromisoformat(args["from"]),
                        date.fromisoformat(args["to"]),
                        args.get("practitionerId"),
                    )
                except ShpError as exc:
                    messages.append(_tool_result_message(call.id, {"error": exc.info.code}))
                    continue
                options = [format_slot(s) for s in slots[:5]]
                messages.append(
                    _tool_result_message(call.id, {"slots": [{"slotId": o.slot_id, "when": o.label} for o in options]})
                )
            elif call.name == LIST_MY_APPOINTMENTS:
                try:
                    appts = scheduling.my_appointments(patient_id)
                except ShpError as exc:
                    messages.append(_tool_result_message(call.id, {"error": exc.info.code}))
                    continue
                messages.append(
                    _tool_result_message(
                        call.id, {"appointments": [{"appointmentId": a["id"], "start": a.get("start")} for a in appts]}
                    )
                )
            elif call.name == PROPOSE_BOOKING:
                summary = "Đặt lịch khám lúc đã chọn"
                if args.get("comment"):
                    summary += f" — {args['comment']}"
                proposal = PendingProposal("book_appointment", {**args, "patientId": patient_id}, summary)
                return TurnResult(
                    "Mình đã chuẩn bị đề xuất đặt lịch, bạn bấm xác nhận trên giao diện để hoàn tất giúp mình nhé.",
                    options,
                    proposal,
                    round_no,
                    attempts,
                )
            else:  # PROPOSE_CANCEL
                proposal = PendingProposal("cancel_appointment", args, "Hủy lịch hẹn đã chọn")
                return TurnResult(
                    "Mình đã chuẩn bị đề xuất hủy lịch, bạn bấm xác nhận trên giao diện để hoàn tất giúp mình nhé.",
                    options,
                    proposal,
                    round_no,
                    attempts,
                )
    return TurnResult(DEFAULT_REPLY_NO_CONVERGENCE, options, rounds_used=MAX_ROUNDS, attempts=attempts)
