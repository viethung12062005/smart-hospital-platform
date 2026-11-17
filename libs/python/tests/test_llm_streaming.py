"""Provider and router streaming (AI-09): order, split placeholders, reset, accounting, breaker, first-token metric."""

import json
from collections.abc import Sequence
from typing import Any

import httpx
import pytest

from shp_platform.llm import (
    ChainStep,
    ChatMessage,
    FakeProvider,
    LlmError,
    Router,
    StreamDelta,
    StreamEnd,
    StreamReset,
    TaskConfig,
)
from shp_platform.llm.provider import OpenAICompatibleProvider
from shp_platform.llm.router import CircuitBreaker, TokenBudget
from shp_platform.pii import Redaction, StreamRestorer, redact

TASKS = {
    "t": TaskConfig(
        chain=[
            ChainStep("deepseek", "ds"),
            ChainStep("gemini", "gm"),
            ChainStep("ollama", "ol", redact_pii=False),
        ],
        final_fallback="retrieval-only",
    )
}
ASK = [ChatMessage("user", "Call 0912345678 about Nguyen Van An")]


def echo(msgs: Sequence[ChatMessage]) -> str:
    return "Reply: " + msgs[-1].content


def router(ds: FakeProvider, gm: FakeProvider | None = None, ol: FakeProvider | None = None, **kw: object) -> Router:
    providers = [ds, gm or FakeProvider("gemini"), ol or FakeProvider("ollama", external=False)]
    return Router(providers, TASKS, **kw)  # type: ignore[arg-type]


def run(r: Router, **kw: Any) -> list[Any]:
    return list(r.stream("t", ASK, {"PERSON": ["Nguyen Van An"]}, **kw))


def text_of(events: Sequence[Any]) -> str:
    return "".join(e.text for e in events if isinstance(e, StreamDelta))


def test_chunks_arrive_in_order_and_end_matches_complete() -> None:
    ds = FakeProvider("deepseek", reply=lambda m: "abcdefghij" * 5, chunk_size=7)
    events = run(router(ds))
    deltas = [e.text for e in events if isinstance(e, StreamDelta)]
    assert len(deltas) > 3 and "".join(deltas) == "abcdefghij" * 5
    assert isinstance(events[-1], StreamEnd) and events[-1].result.text == "abcdefghij" * 5
    assert not any(isinstance(e, StreamReset) for e in events)
    assert events[-1].result.fallback_level == 0 and events[-1].result.answered


@pytest.mark.parametrize("size", [1, 2, 3, 5, 8, 100])
def test_placeholder_split_across_chunks_is_restored(size: int) -> None:
    ds = FakeProvider("deepseek", reply=echo, chunk_size=size)
    events = run(router(ds))
    sent = ds.received[0][0].content
    assert "0912345678" not in sent and "Nguyen Van An" not in sent  # redacted before leaving
    assert text_of(events) == "Reply: Call 0912345678 about Nguyen Van An"
    assert events[-1].result.text == text_of(events)
    for e in events:  # no placeholder fragment ever reaches the caller
        if isinstance(e, StreamDelta):
            assert "[PHONE" not in e.text and "[PERSON" not in e.text


def test_unknown_placeholder_and_plain_brackets_pass_through() -> None:
    ds = FakeProvider("deepseek", reply=lambda m: "see [1] and [PHONE_9] and [x", chunk_size=2)
    assert text_of(run(router(ds))) == "see [1] and [PHONE_9] and [x"


def test_stream_restorer_matches_batch_restore() -> None:
    red = redact("0912345678 a@b.vn 0987654321")
    out = "x [PHONE_1] y [EMAIL_1] z [PHONE_2] [PHONE_"
    for size in (1, 3, 4, 50):
        sr = StreamRestorer([red])
        got = "".join(sr.feed(out[i : i + size]) for i in range(0, len(out), size)) + sr.flush()
        assert got == red.restore(out)
    assert StreamRestorer([Redaction("x", {})]).feed("[a") == "[a"


def test_reset_on_model_switch_mid_stream_and_usage_of_last_step_only() -> None:
    ds = FakeProvider("deepseek", reply=echo, stream_failures=[("timeout", 2)], chunk_size=4)
    gm = FakeProvider("gemini", reply=lambda m: "second model answer", chunk_size=5)
    seen: list[tuple[str, str, str, int, int]] = []
    r = router(ds, gm, on_call=lambda *a: seen.append(a))
    events = run(r)
    kinds = [type(e).__name__ for e in events]
    assert kinds.index("StreamReset") > 0 and kinds[-1] == "StreamEnd"
    reset = next(e for e in events if isinstance(e, StreamReset))
    assert reset.error == "deepseek:timeout"
    after = events[events.index(reset) + 1 :]
    assert text_of(after) == "second model answer"
    res = events[-1].result
    assert (res.provider, res.fallback_level, res.errors) == ("gemini", 1, ["deepseek:timeout"])
    assert [(s[1], s[2]) for s in seen] == [("ds", "timeout"), ("gm", "success")]


def test_failure_before_first_delta_switches_without_reset() -> None:
    ds = FakeProvider("deepseek", failures=["server"])
    events = run(router(ds, FakeProvider("gemini", reply=lambda m: "ok")))
    assert not any(isinstance(e, StreamReset) for e in events)
    assert text_of(events) == "ok" and events[-1].result.fallback_level == 1


def test_all_models_fail_ends_unanswered_with_reset_for_shown_text() -> None:
    ds = FakeProvider("deepseek", reply=echo, stream_failures=[("server", 1)])
    gm = FakeProvider("gemini", failures=["rate-limited"])
    ol = FakeProvider("ollama", external=False, failures=["server"])
    events = run(router(ds, gm, ol))
    assert [type(e).__name__ for e in events] == ["StreamDelta", "StreamReset", "StreamEnd"]
    res = events[-1].result
    assert not res.answered and res.final_fallback == "retrieval-only"
    assert res.errors == ["deepseek:server", "gemini:rate-limited", "ollama:server"]


def test_usage_budget_and_metrics_accounted_in_stream_mode() -> None:
    budget = TokenBudget(daily_limit=10_000, today=lambda: "2026-11-01")
    seen: list[tuple[str, str, str, int, int]] = []
    ds = FakeProvider("deepseek", reply=lambda m: "x" * 80, chunk_size=10)
    res = run(router(ds, budget=budget, on_call=lambda *a: seen.append(a)))[-1].result
    assert res.tokens_out == 20 and res.tokens_in > 0
    assert budget.used() == res.tokens_in + res.tokens_out
    assert seen == [("deepseek", "ds", "success", res.tokens_in, 20)]


def test_budget_exhausted_streams_nothing() -> None:
    budget = TokenBudget(daily_limit=1, today=lambda: "d")
    budget.add(5)
    ds = FakeProvider("deepseek")
    events = run(router(ds, budget=budget))
    assert len(events) == 1 and events[0].result.budget_exceeded and not ds.received


def test_breaker_counts_stream_failures_and_skips_open_circuit() -> None:
    now = [0.0]
    cb = CircuitBreaker(failure_threshold=2, clock=lambda: now[0])
    ds = FakeProvider("deepseek", failures=["server", "server"])
    r = router(ds, FakeProvider("gemini", reply=lambda m: "g"), breaker=cb)
    run(r)
    run(r)
    events = run(r)
    assert len(ds.received) == 2 and events[-1].result.errors == ["deepseek:circuit-open"]


def test_start_level_skips_earlier_steps() -> None:
    ds = FakeProvider("deepseek", reply=lambda m: "a")
    events = run(router(ds, FakeProvider("gemini", reply=lambda m: "b")), start_level=1)
    assert text_of(events) == "b" and not ds.received


def test_closing_early_accounts_estimated_tokens_and_stops_provider() -> None:
    budget = TokenBudget(daily_limit=10_000, today=lambda: "d")
    seen: list[tuple[str, str, str, int, int]] = []
    ds = FakeProvider("deepseek", reply=lambda m: "y" * 400, chunk_size=40)
    s = router(ds, budget=budget, on_call=lambda *a: seen.append(a)).stream("t", ASK)
    next(s)
    next(s)
    s.close()
    assert ds.finished == 0 and seen[0][2] == "aborted" and seen[0][4] == 20
    assert budget.used() > 0 and s.result.provider == "deepseek"


def test_first_token_metric_uses_injected_clock_and_fires_before_provider_finishes() -> None:
    now = [100.0]
    first: list[tuple[str, str, float]] = []
    order: list[str] = []

    def tick(i: int) -> None:  # a slow model: 2 s before the first delta, 1 s between deltas
        now[0] += 2.0 if i == 0 else 1.0
        order.append(f"gen{i}")

    ds = FakeProvider("deepseek", reply=lambda m: "abcdefgh", chunk_size=2, before_chunk=tick)
    r = router(ds, on_first_token=lambda p, m, s: first.append((p, m, s)), clock=lambda: now[0])
    for e in r.stream("t", ASK):
        if isinstance(e, StreamDelta):
            order.append("delta")
            if len([o for o in order if o == "delta"]) == 1:
                assert ds.finished == 0  # first delta delivered while the provider is still generating
        if isinstance(e, StreamEnd):
            order.append("end")
    assert first == [("deepseek", "ds", 2.0)]
    assert order[:3] == ["gen0", "delta", "gen1"] and order[-1] == "end" and ds.finished == 1


def test_provider_without_stream_is_delivered_as_one_delta() -> None:
    class Plain:
        name, external = "deepseek", False

        def complete(self, model: str, messages: Sequence[ChatMessage], max_tokens: int, timeout: float, tools=None):  # type: ignore[no-untyped-def]
            from shp_platform.llm.provider import Completion

            return Completion("whole", model, 3, 1, 1)

    r = Router([Plain(), FakeProvider("gemini"), FakeProvider("ollama", external=False)], TASKS)
    events: list[Any] = list(r.stream("t", ASK))
    assert text_of(events) == "whole" and events[-1].result.tokens_out == 1


# ---- OpenAI-compatible adapter -------------------------------------------------------------------------------


def sse(*events: object, done: bool = True) -> bytes:
    lines = [f"data: {json.dumps(e)}\n\n" for e in events]
    if done:
        lines.append("data: [DONE]\n\n")
    return "".join(lines).encode()


def delta(text: str | None) -> dict[str, object]:
    return {"model": "m1", "choices": [{"delta": {"content": text}}]}


def adapter(handler: object) -> OpenAICompatibleProvider:
    http = httpx.Client(transport=httpx.MockTransport(handler))  # type: ignore[arg-type]
    return OpenAICompatibleProvider("deepseek", "https://api.example/v1", "k", True, http)


def test_adapter_streams_deltas_and_reports_usage() -> None:
    seen: dict[str, object] = {}

    def handler(req: httpx.Request) -> httpx.Response:
        seen.update(json.loads(req.content))
        body = sse(
            delta(""),
            delta("Hel"),
            {"model": "m1", "choices": [{"delta": {}}]},
            delta("lo"),
            {"model": "m1", "choices": [], "usage": {"prompt_tokens": 9, "completion_tokens": 2}},
        )
        return httpx.Response(200, content=b": keep-alive\n\n" + body)

    items: list[Any] = list(adapter(handler).stream("m", [ChatMessage("user", "x")], 10, 5))
    assert items[:2] == ["Hel", "lo"]
    c = items[-1]
    assert (c.text, c.model, c.tokens_in, c.tokens_out, c.usage_estimated) == ("Hello", "m1", 9, 2, False)
    assert seen["stream"] is True and seen["stream_options"] == {"include_usage": True}


def test_adapter_estimates_usage_when_provider_reports_none() -> None:
    items: list[Any] = list(
        adapter(lambda req: httpx.Response(200, content=sse(delta("abcdefgh")))).stream(
            "m", [ChatMessage("user", "abcd")], 10, 5
        )
    )
    c = items[-1]
    assert c.usage_estimated and (c.tokens_in, c.tokens_out) == (1, 2)


@pytest.mark.parametrize(("status", "kind"), [(429, "rate-limited"), (503, "server"), (400, "bad-request")])
def test_adapter_stream_maps_http_errors(status: int, kind: str) -> None:
    with pytest.raises(LlmError) as e:
        list(adapter(lambda req: httpx.Response(status)).stream("m", [ChatMessage("user", "x")], 10, 5))
    assert e.value.kind == kind


def test_adapter_stream_errors_after_first_delta_and_bad_payloads() -> None:
    got: list[object] = []
    with pytest.raises(LlmError) as e:
        for item in adapter(lambda req: httpx.Response(200, content=sse(delta("a"), "not-an-object"))).stream(
            "m", [ChatMessage("user", "x")], 10, 5
        ):
            got.append(item)
    assert got == ["a"] and e.value.kind == "invalid-response"
    with pytest.raises(LlmError) as e2:  # a stream with no text at all
        list(
            adapter(lambda req: httpx.Response(200, content=sse(delta(None)))).stream(
                "m", [ChatMessage("user", "x")], 10, 5
            )
        )
    assert e2.value.kind == "invalid-response"
    broken = sse(delta("a"), done=False) + b"data: {broken\n\ndata: [DONE]\n\n"
    with pytest.raises(LlmError) as e3:
        list(adapter(lambda req: httpx.Response(200, content=broken)).stream("m", [ChatMessage("user", "x")], 10, 5))
    assert e3.value.kind == "invalid-response"


def test_adapter_stream_timeout_and_transport_error() -> None:
    def slow(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("t", request=req)

    with pytest.raises(LlmError) as e:
        list(adapter(slow).stream("m", [ChatMessage("user", "x")], 10, 5))
    assert e.value.kind == "timeout"

    def down(req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("c", request=req)

    with pytest.raises(LlmError) as e2:
        list(adapter(down).stream("m", [ChatMessage("user", "x")], 10, 5))
    assert e2.value.kind == "server"


def test_adapter_stream_enforces_total_time_budget_with_injected_clock() -> None:
    now = [0.0]

    class Ticking(httpx.SyncByteStream):
        def __iter__(self):  # type: ignore[no-untyped-def]
            for t in ("a", "b", "c"):
                now[0] += 4.0
                yield sse(delta(t), done=False)

    http = httpx.Client(transport=httpx.MockTransport(lambda req: httpx.Response(200, stream=Ticking())))
    p = OpenAICompatibleProvider("deepseek", "https://a/v1", "", True, http, clock=lambda: now[0])
    got: list[object] = []
    with pytest.raises(LlmError) as e:
        for item in p.stream("m", [ChatMessage("user", "x")], 10, 6.0):
            got.append(item)
    assert e.value.kind == "timeout" and got == ["a"]
