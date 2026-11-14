"""AI7 `POST /api/v1/ai/booking/chat`, AI8 `POST /api/v1/ai/booking/actions/{actionId}/confirm` (12 §5, US-062)."""

from collections.abc import Callable
from typing import Annotated, Any

from fastapi import APIRouter, Depends, FastAPI, Header, Request
from pydantic import BaseModel, Field

from shp_ai import main, usage
from shp_ai.assistant import conversations as conv
from shp_ai.booking import pending
from shp_ai.booking.scheduling import SchedulingClient, SlotConflictError
from shp_ai.booking.service import TASK, BookingUnavailableError, format_slot, run_turn
from shp_ai.deps import Deps, record_audit
from shp_ai.llm_config import cost_usd
from shp_platform.errors import ShpError
from shp_platform.llm import ChatMessage
from shp_platform.security import Authenticator, Principal

KIND = "booking-bot"
UNAVAILABLE_FALLBACK = {"type": "booking-form", "url": "/booking"}


class ChatBody(BaseModel):
    conversationId: str | None = Field(default=None, max_length=64)  # noqa: N815 - per the API contract
    message: str = Field(min_length=1, max_length=1000)


def ensure_indexes(db: Any) -> None:
    pending.ensure_indexes(db)


def register(app: FastAPI, deps: Callable[[], Deps], auth: Authenticator) -> None:
    r = APIRouter(prefix="/api/v1/ai/booking")
    patient = auth.require_roles("patient")

    @r.post("/chat")
    def chat(request: Request, body: ChatBody, p: Annotated[Principal, Depends(patient)]) -> dict[str, Any]:
        d = deps()
        cid = conv.open_conversation(d.db, p.subject, body.conversationId, KIND)
        history = [
            ChatMessage(m["role"], m["content"])
            for m in d.db.messages.find({"conversationId": cid, "role": {"$in": ["user", "assistant"]}}).sort(
                "createdAt", 1
            )
        ]
        scheduling = SchedulingClient(d.settings.scheduling_url, lambda: p.token, d.extras.get("scheduling_http"))
        try:
            turn = run_turn(d.router, scheduling, p.subject, history, body.message)
        except BookingUnavailableError:
            record_audit(
                d,
                p,
                request,
                action="ai-query",
                resource=f"Conversation/{cid}",
                outcome="error",
                reason="all models unavailable",
            )
            raise ShpError("ai-unavailable", None) from None
        cfg = d.extras["llm_config"]
        for attempt in turn.attempts:
            usage.record(
                d.db, TASK, attempt, cost_usd(cfg, attempt.provider, attempt.tokens_in, attempt.tokens_out), "patient"
            )
        conv.add_message(d.db, cid, "user", body.message)
        conv.add_message(d.db, cid, "assistant", turn.reply)
        action_payload = None
        if turn.proposal is not None:
            action = pending.create(d.db, cid, turn.proposal.tool, turn.proposal.arguments)
            action_payload = {
                "actionId": action["_id"],
                "summary": turn.proposal.summary,
                "expiresAt": action["expiresAt"].isoformat().replace("+00:00", "Z"),
            }
        record_audit(
            d, p, request, action="ai-query", resource=f"Conversation/{cid}", reason=f"rounds={turn.rounds_used}"
        )
        return {
            "conversationId": cid,
            "reply": turn.reply,
            "options": [{"slotId": o.slot_id, "label": o.label} for o in turn.options],
            "pendingAction": action_payload,
        }

    @r.post("/actions/{action_id}/confirm")
    def confirm(
        request: Request,
        action_id: str,
        p: Annotated[Principal, Depends(patient)],
        idempotency_key: Annotated[str | None, Header()] = None,
    ) -> dict[str, Any]:
        if not idempotency_key:
            raise ShpError("idempotency-key-required")
        d = deps()
        # the conversation owner is identified by the conversationId stored in the action; find the patient's
        # existing conversation with the bot
        own_convo_ids = {c["_id"] for c in d.db.conversations.find({"userSubject": p.subject, "kind": KIND})}
        action = d.db.pending_actions.find_one({"_id": action_id})
        if action is None or action["conversationId"] not in own_convo_ids:
            raise ShpError("forbidden" if action else "not-found")
        action = pending.get_for_confirm(d.db, action_id, action["conversationId"])
        scheduling = SchedulingClient(d.settings.scheduling_url, lambda: p.token, d.extras.get("scheduling_http"))
        try:
            if action["tool"] == pending.BOOK:
                result = scheduling.book(
                    action["arguments"]["slotId"], p.subject, action["arguments"].get("comment"), idempotency_key
                )
            else:
                etag = request.headers.get("if-match", "*")
                result = scheduling.cancel(action["arguments"]["appointmentId"], etag, idempotency_key)
        except SlotConflictError as exc:
            record_audit(d, p, request, action="update", resource=f"PendingAction/{action_id}", outcome="denied")
            return {
                "status": "conflict",
                "options": [{"slotId": s.get("id"), "label": format_slot(s).label} for s in exc.suggestions[:3]],
            }
        except ShpError:
            record_audit(d, p, request, action="update", resource=f"PendingAction/{action_id}", outcome="error")
            raise
        pending.mark_confirmed(d.db, action_id)
        record_audit(d, p, request, action="update", resource=f"PendingAction/{action_id}")
        return {"status": "done", "result": result}

    app.include_router(r)


main.EXTRA_ROUTERS.append(register)
