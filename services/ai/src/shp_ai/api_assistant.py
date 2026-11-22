"""AI1 (ask the assistant, JSON or SSE), AI2 (feedback), AI3 (history), AI10 (usage) — 12 §2, §6."""

import json
import logging
import time
from collections.abc import Callable, Iterator
from datetime import date
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Depends, FastAPI, Query, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field

from shp_ai import main, usage
from shp_ai.assistant import conversations as conv
from shp_ai.assistant.service import (
    DISCLAIMER,
    NO_ANSWER,
    TASK,
    AskEvent,
    AskResult,
    FinalEvent,
    ResetEvent,
    TokenEvent,
    ask,
    ask_stream,
)
from shp_ai.deps import Deps, record_audit
from shp_ai.kb.index import HOSPITAL_WIDE, Scope
from shp_ai.llm_config import cost_usd
from shp_platform.errors import ShpError, error_info, problem_response
from shp_platform.pii import redact
from shp_platform.security import Authenticator, Principal

LOG = logging.getLogger("shp.ai.assistant")


class AskBody(BaseModel):
    conversationId: str | None = Field(default=None, max_length=64)  # noqa: N815 - field name per the contract
    # 3-1,000 length check happens in the guardrail (returns answered=false)
    question: str = Field(min_length=1, max_length=2000)
    department: str | None = Field(default=None, pattern=r"^[a-z][a-z-]{1,40}$")


class FeedbackBody(BaseModel):
    rating: Literal["up", "down"]
    comment: str | None = Field(default=None, max_length=500)


def _payload(res: AskResult, conversation_id: str, message_id: str, cost: float) -> dict[str, Any]:
    r = res.router
    out: dict[str, Any] = {
        "conversationId": conversation_id,
        "messageId": message_id,
        "answer": res.answer if res.answered else (res.answer if res.mode != "retrieval-only" else ""),
        "citations": res.citations,
        "answered": res.answered,
        "disclaimer": DISCLAIMER,
    }
    if res.mode in ("retrieval-only", "no-context"):
        out["mode"] = res.mode
    if res.guardrail != "passed":
        out["guardrail"] = res.guardrail
    if r is not None and r.answered:
        out["model"] = {"provider": r.provider, "name": r.model, "fallbackLevel": r.fallback_level}
        out["usage"] = {"inputTokens": r.tokens_in, "outputTokens": r.tokens_out, "costUsd": cost}
    return out


def _ev(name: str, data: Any) -> bytes:
    return f"event: {name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode()


def _tail(payload: dict[str, Any]) -> Iterator[bytes]:
    """The events that close a stream: the citations, then `done` with everything except answer and citations."""
    yield _ev("citations", payload["citations"])
    yield _ev("done", {k: v for k, v in payload.items() if k not in ("answer", "citations")})


def register(app: FastAPI, deps: Callable[[], Deps], auth: Authenticator) -> None:
    r = APIRouter(prefix="/api/v1/ai")
    staff = auth.require_roles("doctor", "nurse")
    owner = auth.require_roles("doctor", "nurse", "patient")

    def settle(
        d: Deps, p: Principal, request: Request, body: AskBody, cid: str, res: AskResult, role: str, outcome: str = ""
    ) -> dict[str, Any]:
        """Usage rows, stored messages and the audit record of one ask, whatever the transport. Returns the payload."""
        cfg = d.extras["llm_config"]
        cost = 0.0
        for attempt in res.attempts:
            c = cost_usd(cfg, attempt.provider, attempt.tokens_in, attempt.tokens_out)
            cost += c
            usage.record(d.db, TASK, attempt, c, role)
        r_ = res.router
        conv.add_message(d.db, cid, "user", redact(body.question).text)
        mid = conv.add_message(
            d.db,
            cid,
            "assistant",
            res.answer,
            citations=res.citations,
            guardrail=res.guardrail,
            promptVersion=res.prompt_version,
            model=r_.model if r_ else None,
            provider=r_.provider if r_ else None,
            fallbackLevel=r_.fallback_level if r_ else None,
            mode=res.mode,
            tokens={"in": r_.tokens_in, "out": r_.tokens_out} if r_ else None,
            costUsd=round(cost, 6),
            latencyMs=r_.latency_ms if r_ else None,
        )
        reason = (
            f"mode={res.mode} guardrail={res.guardrail} provider={r_.provider if r_ else '-'} "
            f"fallback={r_.fallback_level if r_ else '-'} tokens={(r_.tokens_in + r_.tokens_out) if r_ else 0}"
        )
        record_audit(
            d,
            p,
            request,
            action="ai-query",
            resource=f"Conversation/{cid}",
            outcome=outcome or ("denied" if res.guardrail.startswith("blocked") else "success"),
            reason=reason[:200],
        )
        return _payload(res, cid, mid, round(cost, 6))

    def budget_response(request: Request, res: AskResult) -> JSONResponse:
        resp = problem_response(request, error_info("ai-budget-exceeded"))
        body_ = json.loads(bytes(resp.body))
        body_.update({"mode": "retrieval-only", "citations": res.citations})
        keep = {k: v for k, v in resp.headers.items() if k.lower() in ("x-request-id", "retry-after")}
        return JSONResponse(body_, status_code=503, headers=keep, media_type="application/problem+json")

    def sse_response(
        d: Deps, p: Principal, request: Request, body: AskBody, cid: str, scope: Scope, role: str
    ) -> Response:
        res = AskResult(False, NO_ANSWER, [])
        clock = d.extras.get("clock", time.monotonic)
        events = ask_stream(
            d.router,
            d.index,
            d.embedder,
            body.question,
            scope,
            res,
            clock,
            d.extras.get("on_first_token", usage.on_first_token),
        )
        try:
            first = next(events)  # blocks until the first token (or the end), so a 503 can still be a status code
        except BaseException:
            events.close()
            raise
        if isinstance(first, FinalEvent) and res.budget_exceeded:
            settle(d, p, request, body, cid, res, role)
            return budget_response(request, res)

        def body_iter() -> Iterator[bytes]:
            finished = False
            shown = False
            try:
                ev: AskEvent | None = first
                while ev is not None:
                    if isinstance(ev, TokenEvent):
                        shown = True
                        yield _ev("token", {"text": ev.text})
                    elif isinstance(ev, ResetEvent):
                        shown = False
                        usage.STREAM_RESETS.labels(reason=ev.reason).inc()
                        yield _ev("reset", {})
                    else:
                        if not shown and res.answer and res.mode in ("no-context", "guardrail"):
                            yield _ev("token", {"text": res.answer})  # no model ran: the fixed notice
                        payload = settle(d, p, request, body, cid, res, role)
                        finished = True
                        yield from _tail(payload)
                        return
                    ev = next(events, None)
            except Exception as exc:  # noqa: BLE001 - the stream has started, the status code is already 200
                LOG.warning("assistant stream failed: %s", type(exc).__name__)
                yield _ev("error", {"code": "ai-unavailable"})
            finally:
                events.close()
                if not finished:  # client left or the stream broke: the tokens already spent are still accounted
                    try:
                        settle(d, p, request, body, cid, res, role, outcome="error")
                    except Exception as exc:  # noqa: BLE001
                        LOG.warning("could not settle aborted stream: %s", type(exc).__name__)

        return StreamingResponse(
            body_iter(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    @r.post("/assistant/ask", response_model=None)
    def ask_endpoint(
        request: Request, body: AskBody, p: Annotated[Principal, Depends(staff)]
    ) -> Response | dict[str, Any]:
        d = deps()
        own = d.directory.departments(p)
        if body.department and body.department != HOSPITAL_WIDE and body.department not in own:
            raise ShpError("forbidden")
        departments = (body.department,) if body.department else own
        role = "doctor" if "doctor" in p.roles else "nurse"
        cid = conv.open_conversation(d.db, p.subject, body.conversationId, "assistant")
        scope = Scope(role, departments)
        if "text/event-stream" in request.headers.get("accept", ""):
            return sse_response(d, p, request, body, cid, scope, role)
        res = ask(d.router, d.index, d.embedder, body.question, scope)
        payload = settle(d, p, request, body, cid, res, role)
        if res.budget_exceeded:
            return budget_response(request, res)
        return payload

    def _feedback(message_id: str, body: FeedbackBody, p: Principal, replace: bool) -> Response:
        conv.feedback(deps().db, p.subject, message_id, body.rating, body.comment, replace)
        return Response(status_code=204)

    @r.post("/messages/{message_id}/feedback", status_code=204)
    def feedback(message_id: str, body: FeedbackBody, p: Annotated[Principal, Depends(owner)]) -> Response:
        return _feedback(message_id, body, p, False)

    @r.put("/messages/{message_id}/feedback", status_code=204)
    def feedback_update(message_id: str, body: FeedbackBody, p: Annotated[Principal, Depends(owner)]) -> Response:
        return _feedback(message_id, body, p, True)

    @r.get("/conversations")
    def conversations(
        p: Annotated[Principal, Depends(owner)], kind: Literal["assistant", "booking-bot"] | None = None
    ) -> dict[str, Any]:
        return {"items": conv.list_conversations(deps().db, p.subject, kind)}

    @r.get("/conversations/{conversation_id}")
    def conversation(conversation_id: str, p: Annotated[Principal, Depends(owner)]) -> dict[str, Any]:
        return conv.get_conversation(deps().db, p.subject, conversation_id)

    @r.get("/usage")
    def usage_report(
        _: Annotated[Principal, Depends(auth.require_roles("admin"))],
        from_: Annotated[date | None, Query(alias="from")] = None,
        to: date | None = None,
        groupBy: Literal["task", "model", "day"] = "task",  # noqa: N803
    ) -> dict[str, Any]:
        start, end = usage.default_range()
        return {"items": usage.summary(deps().db, from_ or start, to or end, groupBy)}

    app.include_router(r)


main.EXTRA_ROUTERS.append(register)
