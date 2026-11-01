"""Giao diện provider và adapter OpenAI-compatible (PY-07, ADR-0013).

DeepSeek, Gemini (endpoint OpenAI-compatible) và Ollama đều nói chung một API /chat/completions, nên một adapter đủ.
Lỗi được chuẩn hóa: timeout, rate-limited (429), server (5xx), bad-request (4xx khác) để router quyết định chuyển model.
"""

import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Literal, Protocol

import httpx

Role = Literal["system", "user", "assistant", "tool"]
ErrorKind = Literal["timeout", "rate-limited", "server", "bad-request", "invalid-response"]


@dataclass(frozen=True)
class ChatMessage:
    role: Role
    content: str
    # chỉ dùng với role="tool": id của ToolCall đang trả lời (OpenAI-compatible yêu cầu)
    tool_call_id: str | None = None
    # chỉ dùng với role="assistant" khi chính lượt này đã gọi tool (để ghép lại lịch sử hội thoại)
    tool_calls: "tuple[ToolCall, ...] | None" = None


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: str  # JSON thô do model trả; người gọi tự json.loads và validate (không tin cậy mù)


@dataclass(frozen=True)
class Completion:
    text: str
    model: str
    tokens_in: int
    tokens_out: int
    latency_ms: int
    tool_calls: tuple[ToolCall, ...] = ()


class LlmError(Exception):
    def __init__(self, kind: ErrorKind, provider: str) -> None:
        self.kind = kind
        self.provider = provider
        super().__init__(f"{provider}: {kind}")


class Provider(Protocol):
    name: str
    external: bool  # True: nằm ngoài Azure → bắt buộc che PII

    def complete(
        self,
        model: str,
        messages: Sequence[ChatMessage],
        max_tokens: int,
        timeout_seconds: float,
        tools: Sequence[dict[str, object]] | None = None,
    ) -> Completion: ...


class OpenAICompatibleProvider:
    def __init__(
        self, name: str, base_url: str, api_key: str, external: bool, http: httpx.Client | None = None
    ) -> None:
        self.name = name
        self.external = external
        self._url = base_url.rstrip("/") + "/chat/completions"
        self._headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._http = http or httpx.Client()

    @staticmethod
    def _wire_message(m: ChatMessage) -> dict[str, object]:
        out: dict[str, object] = {"role": m.role, "content": m.content}
        if m.tool_call_id:
            out["tool_call_id"] = m.tool_call_id
        if m.tool_calls:
            out["tool_calls"] = [
                {"id": t.id, "type": "function", "function": {"name": t.name, "arguments": t.arguments}}
                for t in m.tool_calls
            ]
        return out

    def complete(
        self,
        model: str,
        messages: Sequence[ChatMessage],
        max_tokens: int,
        timeout_seconds: float,
        tools: Sequence[dict[str, object]] | None = None,
    ) -> Completion:
        started = time.monotonic()
        payload: dict[str, object] = {
            "model": model,
            "messages": [self._wire_message(m) for m in messages],
            "max_tokens": max_tokens,
            "temperature": 0.2,
        }
        if tools:
            payload["tools"] = list(tools)
        try:
            res = self._http.post(self._url, headers=self._headers, json=payload, timeout=timeout_seconds)
        except httpx.TimeoutException as exc:
            raise LlmError("timeout", self.name) from exc
        except httpx.HTTPError as exc:
            raise LlmError("server", self.name) from exc
        if res.status_code == 429:
            raise LlmError("rate-limited", self.name)
        if res.status_code >= 500:
            raise LlmError("server", self.name)
        if res.status_code >= 400:
            raise LlmError("bad-request", self.name)
        try:
            body = res.json()
            message = body["choices"][0]["message"]
            text = message.get("content")
            raw_calls = message.get("tool_calls") or []
            usage = body.get("usage") or {}
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise LlmError("invalid-response", self.name) from exc
        if text is None and not raw_calls:
            raise LlmError("invalid-response", self.name)
        if text is not None and not isinstance(text, str):
            raise LlmError("invalid-response", self.name)
        try:
            tool_calls = tuple(
                ToolCall(id=c["id"], name=c["function"]["name"], arguments=c["function"]["arguments"])
                for c in raw_calls
            )
        except (KeyError, TypeError) as exc:
            raise LlmError("invalid-response", self.name) from exc
        return Completion(
            text=text or "",
            model=str(body.get("model", model)),
            tokens_in=int(usage.get("prompt_tokens", 0)),
            tokens_out=int(usage.get("completion_tokens", 0)),
            latency_ms=int((time.monotonic() - started) * 1000),
            tool_calls=tool_calls,
        )


class FakeProvider:
    """Provider giả cho dev và test: trả lời theo hàm hoặc ném lỗi theo kịch bản; ghi lại prompt đã nhận."""

    def __init__(
        self,
        name: str = "fake",
        external: bool = True,
        reply: Callable[[Sequence[ChatMessage]], str] | None = None,
        tool_reply: Callable[[Sequence[ChatMessage]], tuple[ToolCall, ...] | None] | None = None,
        failures: Sequence[ErrorKind] = (),
    ) -> None:
        self.name = name
        self.external = external
        self._reply = reply or (lambda msgs: "ok: " + msgs[-1].content[:50])
        self._tool_reply = tool_reply
        self._failures = list(failures)
        self.received: list[list[ChatMessage]] = []
        self.received_tools: list[Sequence[dict[str, object]] | None] = []

    def complete(
        self,
        model: str,
        messages: Sequence[ChatMessage],
        max_tokens: int,
        timeout_seconds: float,
        tools: Sequence[dict[str, object]] | None = None,
    ) -> Completion:
        self.received.append(list(messages))
        self.received_tools.append(tools)
        if self._failures:
            raise LlmError(self._failures.pop(0), self.name)
        calls = self._tool_reply(messages) if self._tool_reply else None
        if calls:
            return Completion("", model, sum(len(m.content) // 4 for m in messages), 10, 1, tuple(calls))
        text = self._reply(messages)
        return Completion(text, model, sum(len(m.content) // 4 for m in messages), len(text) // 4, 1)
