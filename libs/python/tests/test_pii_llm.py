from collections.abc import Sequence

import httpx
import pytest

from shp_platform.errors import ShpError
from shp_platform.llm import ChainStep, ChatMessage, FakeProvider, Router, TaskConfig
from shp_platform.llm.provider import LlmError, OpenAICompatibleProvider
from shp_platform.llm.router import CircuitBreaker, TokenBudget
from shp_platform.pii import contains_pii, redact
from shp_platform.token_exchange import ExchangeSettings, TokenExchanger

TEXT = (
    "Bệnh nhân Nguyễn Văn An, sinh 12/03/1980, SĐT 0912 345 678, email an.nguyen@example.com, "
    "CCCD 001080012345, thẻ BHYT DN4790123456789, ở 12 Lê Lợi. Gọi lại +84912345678."
)


def test_redact_removes_all_pii_and_restores() -> None:
    r = redact(TEXT, {"PERSON": ["Nguyễn Văn An"], "ADDRESS": ["12 Lê Lợi"]})
    for secret in [
        "Nguyễn Văn An",
        "0912 345 678",
        "an.nguyen@example.com",
        "001080012345",
        "DN4790123456789",
        "12 Lê Lợi",
        "12/03/1980",
        "+84912345678",
    ]:
        assert secret not in r.text, secret
    assert not contains_pii(r.text)
    assert r.restore(r.text) == TEXT
    assert "Nguyễn" not in repr(r)


def test_same_value_same_token() -> None:
    r = redact("0912345678 và 0912345678", None)
    assert r.text == "[PHONE_1] và [PHONE_1]"


def test_medical_numbers_are_not_redacted() -> None:
    text = "Huyết áp 120/80 mmHg, nhịp tim 72, đường huyết 5.6 mmol/L"
    assert redact(text).text == text


TASKS = {
    "rag-answer": TaskConfig(
        chain=[
            ChainStep("deepseek", "deepseek-chat"),
            ChainStep("gemini", "gemini-flash"),
            ChainStep("ollama", "qwen", redact_pii=False),
        ],
        final_fallback="retrieval-only",
    )
}


def _router(ds: FakeProvider, gm: FakeProvider, ol: FakeProvider, **kw: object) -> Router:
    return Router([ds, gm, ol], TASKS, **kw)  # type: ignore[arg-type]


def echo(msgs: Sequence[ChatMessage]) -> str:
    return "Trả lời cho " + msgs[-1].content


def test_router_redacts_for_external_and_restores_answer() -> None:
    ds = FakeProvider("deepseek", reply=echo)
    res = _router(ds, FakeProvider("gemini"), FakeProvider("ollama", external=False)).complete(
        "rag-answer", [ChatMessage("user", "SĐT 0912345678 của Nguyễn Văn An")], {"PERSON": ["Nguyễn Văn An"]}
    )
    sent = ds.received[0][0].content
    assert "0912345678" not in sent and "Nguyễn Văn An" not in sent
    assert res.answered and res.fallback_level == 0 and res.text == "Trả lời cho SĐT 0912345678 của Nguyễn Văn An"


def test_router_falls_back_in_order_then_reports_final_fallback() -> None:
    ds = FakeProvider("deepseek", failures=["timeout"])
    gm = FakeProvider("gemini", failures=["rate-limited"])
    ol = FakeProvider("ollama", external=False, reply=echo)
    res = _router(ds, gm, ol).complete("rag-answer", [ChatMessage("user", "0912345678")])
    assert res.provider == "ollama" and res.fallback_level == 2
    assert ol.received[0][0].content == "0912345678"  # in the cluster, redact_pii=False
    assert res.errors == ["deepseek:timeout", "gemini:rate-limited"]
    all_fail = _router(
        FakeProvider("deepseek", failures=["server"]),
        FakeProvider("gemini", failures=["server"]),
        FakeProvider("ollama", external=False, failures=["server"]),
    )
    res = all_fail.complete("rag-answer", [ChatMessage("user", "x")])
    assert not res.answered and res.final_fallback == "retrieval-only"


def test_external_provider_always_redacted_even_if_config_says_no() -> None:
    ol = FakeProvider("ollama", external=True, reply=echo)
    _router(FakeProvider("deepseek", failures=["server"]), FakeProvider("gemini", failures=["server"]), ol).complete(
        "rag-answer", [ChatMessage("user", "0912345678")]
    )
    assert "0912345678" not in ol.received[0][0].content


def test_circuit_opens_after_5_failures_and_half_opens() -> None:
    now = [0.0]
    cb = CircuitBreaker(clock=lambda: now[0])
    ds = FakeProvider("deepseek", failures=["server"] * 6)
    r = _router(ds, FakeProvider("gemini"), FakeProvider("ollama", external=False), breaker=cb)
    for _ in range(6):
        r.complete("rag-answer", [ChatMessage("user", "x")])
    assert len(ds.received) == 5
    now[0] = 31
    r.complete("rag-answer", [ChatMessage("user", "x")])
    assert len(ds.received) == 6  # half-open: retry once


def test_budget_exhausted_stops_calls() -> None:
    budget = TokenBudget(daily_limit=10, today=lambda: "2026-11-01")
    ds = FakeProvider("deepseek", reply=lambda m: "x" * 100)
    r = _router(ds, FakeProvider("gemini"), FakeProvider("ollama", external=False), budget=budget)
    assert r.complete("rag-answer", [ChatMessage("user", "hello")]).answered
    res = r.complete("rag-answer", [ChatMessage("user", "hello")])
    assert res.budget_exceeded and not res.answered and len(ds.received) == 1


def test_router_rejects_unknown_provider() -> None:
    with pytest.raises(ValueError):
        Router([FakeProvider("deepseek")], TASKS)


@pytest.mark.parametrize(("status", "kind"), [(429, "rate-limited"), (503, "server"), (400, "bad-request")])
def test_openai_adapter_maps_errors(status: int, kind: str) -> None:
    http = httpx.Client(transport=httpx.MockTransport(lambda req: httpx.Response(status)))
    p = OpenAICompatibleProvider("deepseek", "https://api.example/v1", "k", True, http)
    with pytest.raises(LlmError) as e:
        p.complete("m", [ChatMessage("user", "x")], 10, 1)
    assert e.value.kind == kind


def test_openai_adapter_parses_completion_and_timeout() -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        assert req.headers["authorization"] == "Bearer k"
        return httpx.Response(
            200,
            json={
                "model": "m1",
                "choices": [{"message": {"content": "hi"}}],
                "usage": {"prompt_tokens": 3, "completion_tokens": 1},
            },
        )

    p = OpenAICompatibleProvider(
        "deepseek", "https://api.example/v1", "k", True, httpx.Client(transport=httpx.MockTransport(handler))
    )
    c = p.complete("m", [ChatMessage("user", "x")], 10, 1)
    assert (c.text, c.model, c.tokens_in, c.tokens_out) == ("hi", "m1", 3, 1)

    def slow(req: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("t", request=req)

    p = OpenAICompatibleProvider("x", "https://a/v1", "", True, httpx.Client(transport=httpx.MockTransport(slow)))
    with pytest.raises(LlmError) as e:
        p.complete("m", [ChatMessage("user", "x")], 10, 1)
    assert e.value.kind == "timeout"


def test_token_exchange_caches_and_maps_errors() -> None:
    calls: list[bytes] = []

    def handler(req: httpx.Request) -> httpx.Response:
        calls.append(req.content)
        if b"audience=bad" in req.content:
            return httpx.Response(403)
        return httpx.Response(200, json={"access_token": "exchanged", "expires_in": 300})

    now = [0.0]
    tx = TokenExchanger(
        ExchangeSettings("https://kc/token", "ai", "s"),
        httpx.Client(transport=httpx.MockTransport(handler)),
        clock=lambda: now[0],
    )
    assert tx.exchange("user-token", "clinical") == "exchanged"
    assert tx.exchange("user-token", "clinical") == "exchanged"
    assert len(calls) == 1 and b"grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange" in calls[0]
    now[0] = 271
    tx.exchange("user-token", "clinical")
    assert len(calls) == 2
    with pytest.raises(ShpError) as e:
        tx.exchange("user-token", "bad")
    assert e.value.info.code == "forbidden"
