"""PY-07/PY-08: function calling (tool use) via Router/Provider — needed for booking-agent (US-062)."""

import json

import httpx
import pytest

from shp_platform.llm import ChainStep, ChatMessage, FakeProvider, Router, TaskConfig, ToolCall
from shp_platform.llm.provider import LlmError, OpenAICompatibleProvider

TASKS = {"booking-agent": TaskConfig(chain=[ChainStep("deepseek", "deepseek-chat")], final_fallback="booking-form")}


def test_fake_provider_tool_call_round_trip() -> None:
    call = ToolCall(id="call_1", name="search_slots", arguments=json.dumps({"specialty": "cardiology"}))
    ds = FakeProvider("deepseek", tool_reply=lambda msgs: (call,))
    router = Router([ds], TASKS)
    res = router.complete("booking-agent", [ChatMessage("user", "khám tim mạch")])
    assert res.answered and res.text == "" and res.tool_calls == (call,)
    assert ds.received_tools[0] is None  # no tools passed -> still works (optional parameter)


def test_tools_definition_is_forwarded_to_provider() -> None:
    ds = FakeProvider("deepseek")
    router = Router([ds], TASKS)
    tools: list[dict[str, object]] = [{"type": "function", "function": {"name": "search_slots", "parameters": {}}}]
    router.complete("booking-agent", [ChatMessage("user", "x")], tools=tools)
    assert ds.received_tools[0] == tools


def test_redaction_preserves_tool_call_id_and_tool_calls_fields() -> None:
    """When PII must be redacted (non-Azure provider), rebuilding ChatMessage must not drop tool_call_id/tool_calls."""
    seen: list[ChatMessage] = []

    from collections.abc import Sequence

    def capture(msgs: Sequence[ChatMessage]) -> str:
        seen.extend(msgs)
        return "ok"

    ds = FakeProvider("deepseek", external=True, reply=capture)
    router = Router([ds], TASKS)
    call = ToolCall(id="call_1", name="search_slots", arguments="{}")
    history = [
        ChatMessage("user", "SĐT của tôi là 0912345678, tìm lịch tim mạch"),
        ChatMessage("assistant", "", tool_calls=(call,)),
        ChatMessage("tool", json.dumps({"slots": []}), tool_call_id="call_1"),
    ]
    router.complete("booking-agent", history)
    assert "0912345678" not in seen[0].content  # PII is still redacted in natural-language content
    assert seen[1].tool_calls == (call,)  # tool_calls are not dropped when rebuilding after redaction
    assert seen[2].tool_call_id == "call_1"  # tool_call_id is not dropped


def test_openai_adapter_parses_tool_calls_and_allows_null_content() -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        assert body["tools"][0]["function"]["name"] == "search_slots"
        return httpx.Response(
            200,
            json={
                "model": "deepseek-chat",
                "choices": [
                    {
                        "message": {
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "call_1",
                                    "type": "function",
                                    "function": {"name": "search_slots", "arguments": '{"specialty":"cardiology"}'},
                                }
                            ],
                        }
                    }
                ],
                "usage": {"prompt_tokens": 50, "completion_tokens": 12},
            },
        )

    p = OpenAICompatibleProvider(
        "deepseek", "https://api.example/v1", "k", True, httpx.Client(transport=httpx.MockTransport(handler))
    )
    tools: list[dict[str, object]] = [{"type": "function", "function": {"name": "search_slots", "parameters": {}}}]
    c = p.complete("deepseek-chat", [ChatMessage("user", "x")], 100, 5, tools=tools)
    assert c.text == "" and len(c.tool_calls) == 1
    assert c.tool_calls[0] == ToolCall("call_1", "search_slots", '{"specialty":"cardiology"}')


def test_openai_adapter_rejects_both_content_and_tool_calls_missing() -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"choices": [{"message": {"content": None}}]})

    p = OpenAICompatibleProvider(
        "deepseek", "https://api.example/v1", "k", True, httpx.Client(transport=httpx.MockTransport(handler))
    )
    with pytest.raises(LlmError) as e:
        p.complete("m", [ChatMessage("user", "x")], 10, 1)
    assert e.value.kind == "invalid-response"


def test_redaction_does_not_touch_tool_result_content() -> None:
    """role='tool' content is backend JSON (slot/appointment ids...), not user text: do not redact."""
    from collections.abc import Sequence

    seen: list[ChatMessage] = []

    def capture(msgs: Sequence[ChatMessage]) -> str:
        seen.extend(msgs)
        return "ok"

    ds = FakeProvider("deepseek", external=True, reply=capture)
    router = Router([ds], TASKS)
    appointment_id = "33333333-3333-4333-8333-333333333333"
    history = [
        ChatMessage("user", "hủy lịch giúp mình"),
        ChatMessage("assistant", "", tool_calls=(ToolCall("c1", "list_my_appointments", "{}"),)),
        ChatMessage("tool", json.dumps({"appointments": [{"appointmentId": appointment_id}]}), tool_call_id="c1"),
    ]
    router.complete("booking-agent", history)
    assert appointment_id in seen[2].content  # the id is not replaced by [ID_1]
