"""Provider interface and OpenAI-compatible adapter (PY-07, ADR-0013).

DeepSeek, Gemini (OpenAI-compatible endpoint) and Ollama all speak the same /chat/completions API, so one adapter
suffices.
Errors are normalized: timeout, rate-limited (429), server (5xx), bad-request (other 4xx) so the router can decide
to switch models.

Streaming (`stream`): the same request with `stream: true`; the adapter yields each text delta as it arrives and
ends with one `Completion` (full text, usage). A failure after the first delta raises the same `LlmError`, so the
router can tell the caller to discard what was shown and switch models.
"""

import json
import time
from collections.abc import Callable, Iterator, Sequence
from dataclasses import dataclass
from typing import Literal, Protocol, runtime_checkable

import httpx

Role = Literal["system", "user", "assistant", "tool"]
ErrorKind = Literal["timeout", "rate-limited", "server", "bad-request", "invalid-response"]


@dataclass(frozen=True)
class ChatMessage:
    role: Role
    content: str
    # only with role="tool": id of the ToolCall being answered (required by OpenAI-compatible APIs)
    tool_call_id: str | None = None
    # only with role="assistant" when this turn called tools (to rebuild conversation history)
    tool_calls: "tuple[ToolCall, ...] | None" = None


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: str  # raw JSON returned by the model; the caller does json.loads and validates (never trust blindly)


@dataclass(frozen=True)
class Completion:
    text: str
    model: str
    tokens_in: int
    tokens_out: int
    latency_ms: int
    tool_calls: tuple[ToolCall, ...] = ()
    # True when the provider reported no usage and the counts are a 4-characters-per-token estimate (streaming)
    usage_estimated: bool = False


def estimate_tokens(text: str) -> int:
    """Rough token count (4 characters per token) for when a provider reports no usage."""
    return len(text) // 4


class LlmError(Exception):
    def __init__(self, kind: ErrorKind, provider: str) -> None:
        self.kind = kind
        self.provider = provider
        super().__init__(f"{provider}: {kind}")


class Provider(Protocol):
    name: str
    external: bool  # True: outside Azure -> PII redaction is mandatory

    def complete(
        self,
        model: str,
        messages: Sequence[ChatMessage],
        max_tokens: int,
        timeout_seconds: float,
        tools: Sequence[dict[str, object]] | None = None,
    ) -> Completion: ...


@runtime_checkable
class StreamingProvider(Protocol):
    """A provider that can stream. `stream` yields text deltas (`str`) and, as its last item, one `Completion`
    holding the full text and the usage. It raises `LlmError` at any point, also after deltas were yielded."""

    def stream(
        self, model: str, messages: Sequence[ChatMessage], max_tokens: int, timeout_seconds: float
    ) -> Iterator["str | Completion"]: ...


class OpenAICompatibleProvider:
    def __init__(
        self,
        name: str,
        base_url: str,
        api_key: str,
        external: bool,
        http: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.name = name
        self.external = external
        self._clock = clock
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

    def stream(
        self, model: str, messages: Sequence[ChatMessage], max_tokens: int, timeout_seconds: float
    ) -> Iterator[str | Completion]:
        """`timeout_seconds` bounds each read and the whole answer (the task's time budget, AC-11.3)."""
        started = self._clock()
        payload: dict[str, object] = {
            "model": model,
            "messages": [self._wire_message(m) for m in messages],
            "max_tokens": max_tokens,
            "temperature": 0.2,
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        parts: list[str] = []
        usage: dict[str, object] = {}
        served_model = model
        try:
            with self._http.stream(
                "POST", self._url, headers=self._headers, json=payload, timeout=timeout_seconds
            ) as res:
                if res.status_code == 429:
                    raise LlmError("rate-limited", self.name)
                if res.status_code >= 500:
                    raise LlmError("server", self.name)
                if res.status_code >= 400:
                    raise LlmError("bad-request", self.name)
                for line in res.iter_lines():
                    if self._clock() - started > timeout_seconds:
                        raise LlmError("timeout", self.name)
                    if not line.startswith("data:"):
                        continue  # blank separators, SSE comments (keep-alive)
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break
                    delta, reported_model = self._parse_stream_event(data, usage)
                    served_model = reported_model or served_model
                    if delta:
                        parts.append(delta)
                        yield delta
        except httpx.TimeoutException as exc:
            raise LlmError("timeout", self.name) from exc
        except httpx.HTTPError as exc:
            raise LlmError("server", self.name) from exc
        text = "".join(parts)
        if not text:
            raise LlmError("invalid-response", self.name)
        reported = "prompt_tokens" in usage or "completion_tokens" in usage
        yield Completion(
            text=text,
            model=served_model,
            tokens_in=_as_int(usage.get("prompt_tokens"), sum(estimate_tokens(m.content) for m in messages)),
            tokens_out=_as_int(usage.get("completion_tokens"), estimate_tokens(text)),
            latency_ms=int((self._clock() - started) * 1000),
            usage_estimated=not reported,
        )

    def _parse_stream_event(self, data: str, usage: dict[str, object]) -> tuple[str, str | None]:
        """One `data:` line of the stream -> (text delta, model name); the delta is "" when the event carries
        none. Fills `usage` when the event reports it."""
        try:
            body = json.loads(data)
            if not isinstance(body, dict):
                raise TypeError("event is not an object")
            if isinstance(body.get("usage"), dict):
                usage.update(body["usage"])
            served = body.get("model") if isinstance(body.get("model"), str) else None
            choices = body.get("choices") or []
            if not choices:
                return "", served  # usage-only final event
            content = (choices[0].get("delta") or {}).get("content")
        except (ValueError, KeyError, IndexError, TypeError, AttributeError) as exc:
            raise LlmError("invalid-response", self.name) from exc
        if content is None:
            return "", served
        if not isinstance(content, str):
            raise LlmError("invalid-response", self.name)
        return content, served


def _as_int(value: object, default: int) -> int:
    return int(value) if isinstance(value, int | float) else default


class FakeProvider:
    """Fake provider for dev and tests: answer via a function or raise scripted errors; records the prompts received.

    Streaming: the answer is cut into `chunk_size`-character deltas (or by `chunker`). `stream_failures` scripts
    failures of streamed calls as `(kind, deltas_before_failing)`. `before_chunk(i)` runs before delta `i` is
    yielded (tests advance a fake clock there instead of sleeping) and `finished` counts streams that reached their
    end, so a test can prove a delta was delivered while the provider was still generating.
    """

    def __init__(
        self,
        name: str = "fake",
        external: bool = True,
        reply: Callable[[Sequence[ChatMessage]], str] | None = None,
        tool_reply: Callable[[Sequence[ChatMessage]], tuple[ToolCall, ...] | None] | None = None,
        failures: Sequence[ErrorKind] = (),
        chunk_size: int = 16,
        chunker: Callable[[str], Sequence[str]] | None = None,
        stream_failures: Sequence[tuple[ErrorKind, int]] = (),
        before_chunk: Callable[[int], None] | None = None,
        report_usage: bool = True,
    ) -> None:
        self.name = name
        self.external = external
        self._reply = reply or (lambda msgs: "ok: " + msgs[-1].content[:50])
        self._tool_reply = tool_reply
        self._failures = list(failures)
        self._chunker = chunker or (lambda t: [t[i : i + chunk_size] for i in range(0, len(t), chunk_size)])
        self._stream_failures = list(stream_failures)
        self._before_chunk = before_chunk
        self._report_usage = report_usage
        self.finished = 0
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

    def stream(
        self, model: str, messages: Sequence[ChatMessage], max_tokens: int, timeout_seconds: float
    ) -> Iterator[str | Completion]:
        self.received.append(list(messages))
        self.received_tools.append(None)
        if self._failures:
            raise LlmError(self._failures.pop(0), self.name)
        failure = self._stream_failures.pop(0) if self._stream_failures else None
        text = self._reply(messages)
        for i, chunk in enumerate(self._chunker(text)):
            if failure is not None and i >= failure[1]:
                raise LlmError(failure[0], self.name)
            if self._before_chunk is not None:
                self._before_chunk(i)
            yield chunk
        if failure is not None:
            raise LlmError(failure[0], self.name)
        self.finished += 1
        tokens_in = sum(estimate_tokens(m.content) for m in messages)
        yield Completion(text, model, tokens_in, estimate_tokens(text), 1, usage_estimated=not self._report_usage)
