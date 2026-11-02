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


@dataclass(frozen=True)
class Completion:
    text: str
    model: str
    tokens_in: int
    tokens_out: int
    latency_ms: int


class LlmError(Exception):
    def __init__(self, kind: ErrorKind, provider: str) -> None:
        self.kind = kind
        self.provider = provider
        super().__init__(f"{provider}: {kind}")


class Provider(Protocol):
    name: str
    external: bool  # True: nằm ngoài Azure → bắt buộc che PII

    def complete(
        self, model: str, messages: Sequence[ChatMessage], max_tokens: int, timeout_seconds: float
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

    def complete(
        self, model: str, messages: Sequence[ChatMessage], max_tokens: int, timeout_seconds: float
    ) -> Completion:
        started = time.monotonic()
        try:
            res = self._http.post(
                self._url,
                headers=self._headers,
                json={
                    "model": model,
                    "messages": [{"role": m.role, "content": m.content} for m in messages],
                    "max_tokens": max_tokens,
                    "temperature": 0.2,
                },
                timeout=timeout_seconds,
            )
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
            text = body["choices"][0]["message"]["content"]
            usage = body.get("usage") or {}
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise LlmError("invalid-response", self.name) from exc
        if not isinstance(text, str):
            raise LlmError("invalid-response", self.name)
        return Completion(
            text=text,
            model=str(body.get("model", model)),
            tokens_in=int(usage.get("prompt_tokens", 0)),
            tokens_out=int(usage.get("completion_tokens", 0)),
            latency_ms=int((time.monotonic() - started) * 1000),
        )


class FakeProvider:
    """Provider giả cho dev và test: trả lời theo hàm hoặc ném lỗi theo kịch bản; ghi lại prompt đã nhận."""

    def __init__(
        self,
        name: str = "fake",
        external: bool = True,
        reply: Callable[[Sequence[ChatMessage]], str] | None = None,
        failures: Sequence[ErrorKind] = (),
    ) -> None:
        self.name = name
        self.external = external
        self._reply = reply or (lambda msgs: "ok: " + msgs[-1].content[:50])
        self._failures = list(failures)
        self.received: list[list[ChatMessage]] = []

    def complete(
        self, model: str, messages: Sequence[ChatMessage], max_tokens: int, timeout_seconds: float
    ) -> Completion:
        self.received.append(list(messages))
        if self._failures:
            raise LlmError(self._failures.pop(0), self.name)
        text = self._reply(messages)
        return Completion(text, model, sum(len(m.content) // 4 for m in messages), len(text) // 4, 1)
