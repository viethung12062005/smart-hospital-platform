"""Per-task model chain router, circuit breaker, daily token budget (PY-08, PY-09, 06-data-ai §4).

- Try each step in the chain in order; on error, open circuit or exhausted budget, move to the next step.
  `fallback_level` = index of the step that answered (0 = primary model). When the chain is exhausted, return
  `answered=False` with `final_fallback` so the service can handle it (retrieval-only, structured-timeline,
  human-review, booking-form).
- Providers outside Azure (`external`) always receive PII-redacted text, even if the config says `redact_pii: false`.
- The circuit opens after `failure_threshold` failures within `window_seconds`, and closes for a retry after
  `open_seconds`.
- Daily token budget exhausted: call no model, and the service returns ai-budget-exceeded.
- `stream` gives the same guarantees while the answer is delivered delta by delta: the prompt is redacted before it
  is sent, placeholders are restored in the streamed text (also when one is split across deltas), usage, budget,
  breaker and `fallback_level` are accounted exactly as in `complete`. A step that fails after it already produced
  text yields `StreamReset` before the next step starts, so the caller discards what it has shown.
"""

import time
from collections import defaultdict, deque
from collections.abc import Callable, Generator, Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from shp_platform.llm.provider import (
    ChatMessage,
    Completion,
    LlmError,
    Provider,
    StreamingProvider,
    estimate_tokens,
)
from shp_platform.pii import Redaction, StreamRestorer, redact


@dataclass(frozen=True)
class ChainStep:
    provider: str
    model: str
    redact_pii: bool = True


@dataclass(frozen=True)
class TaskConfig:
    chain: Sequence[ChainStep]
    final_fallback: str
    timeout_seconds: float = 12.0
    max_output_tokens: int = 800


@dataclass
class RouterResult:
    answered: bool
    text: str = ""
    provider: str = ""
    model: str = ""
    fallback_level: int = -1
    tokens_in: int = 0
    tokens_out: int = 0
    latency_ms: int = 0
    final_fallback: str = ""
    budget_exceeded: bool = False
    tool_calls: tuple[Any, ...] = ()  # ToolCall; empty unless the task calls complete(..., tools=...)
    errors: list[str] = field(default_factory=list)  # "deepseek:timeout"; contains no content


@dataclass(frozen=True)
class StreamDelta:
    """Answer text to append; placeholders are already restored. `provider`/`model`: the chain step producing it."""

    text: str
    provider: str = ""
    model: str = ""


@dataclass(frozen=True)
class StreamReset:
    """The step that produced the text so far failed: discard it, the next step starts over. `error` is
    "provider:kind" and carries no content."""

    error: str


@dataclass(frozen=True)
class StreamEnd:
    """Always the last event. `result` is what `complete` would have returned."""

    result: RouterResult


StreamEvent = StreamDelta | StreamReset | StreamEnd


class RouterStream(Iterator[StreamEvent]):
    """Events of one `Router.stream` call. `result` is filled as the stream advances and is final after the last
    event or after `close()`. Closing early (the caller rejects the text, the client went away) stops the provider
    call and still accounts the tokens consumed so far."""

    def __init__(self, events: Iterator[StreamEvent], result: RouterResult, close: Callable[[], None]) -> None:
        self._events = events
        self.result = result
        self._close = close

    def __iter__(self) -> "RouterStream":
        return self

    def __next__(self) -> StreamEvent:
        return next(self._events)

    def close(self) -> None:
        self._close()


class CircuitBreaker:
    def __init__(
        self,
        failure_threshold: int = 5,
        window_seconds: float = 60,
        open_seconds: float = 30,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._threshold = failure_threshold
        self._window = window_seconds
        self._open = open_seconds
        self._clock = clock
        self._failures: dict[str, deque[float]] = defaultdict(deque)
        self._opened_at: dict[str, float] = {}

    def allow(self, key: str) -> bool:
        opened = self._opened_at.get(key)
        if opened is None:
            return True
        if self._clock() - opened >= self._open:
            del self._opened_at[key]  # half-open: allow one attempt; another failure reopens immediately
            self._failures[key].clear()
            self._failures[key].extend([self._clock()] * (self._threshold - 1))
            return True
        return False

    def success(self, key: str) -> None:
        self._failures[key].clear()

    def failure(self, key: str) -> None:
        now = self._clock()
        q = self._failures[key]
        q.append(now)
        while q and now - q[0] > self._window:
            q.popleft()
        if len(q) >= self._threshold:
            self._opened_at[key] = now


class TokenBudget:
    """Daily token budget (UTC) for the whole service. Multi-replica setups need a shared counter (Redis) - pass a
    different `store`."""

    def __init__(self, daily_limit: int, today: Callable[[], str] | None = None) -> None:
        self.daily_limit = daily_limit
        self._today = today or (lambda: datetime.now(UTC).date().isoformat())
        self._used: dict[str, int] = {}

    def used(self) -> int:
        return self._used.get(self._today(), 0)

    def exhausted(self) -> bool:
        return self.used() >= self.daily_limit

    def add(self, tokens: int) -> None:
        day = self._today()
        self._used = {day: self._used.get(day, 0) + tokens}


class Router:
    def __init__(
        self,
        providers: Iterable[Provider],
        tasks: Mapping[str, TaskConfig],
        breaker: CircuitBreaker | None = None,
        budget: TokenBudget | None = None,
        on_call: Callable[[str, str, str, int, int], None] | None = None,
        on_first_token: Callable[[str, str, float], None] | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        """`on_call(provider, model, outcome, tokens_in, tokens_out)` to export per-model metrics (Prometheus).
        `on_first_token(provider, model, seconds)`: time from sending a streamed request to its first text delta,
        measured with `clock`."""
        self._providers = {p.name: p for p in providers}
        self._tasks = dict(tasks)
        self._breaker = breaker or CircuitBreaker()
        self._budget = budget
        self._on_call = on_call
        self._on_first_token = on_first_token
        self._clock = clock
        for name, cfg in self._tasks.items():
            for step in cfg.chain:
                if step.provider not in self._providers:
                    raise ValueError(f"task {name}: provider not declared: {step.provider}")

    def complete(
        self,
        task: str,
        messages: Sequence[ChatMessage],
        known_pii: Mapping[str, Iterable[str]] | None = None,
        start_level: int = 0,
        tools: Sequence[dict[str, object]] | None = None,
    ) -> RouterResult:
        """`start_level`: skip the first steps (for example retry with the next model when the output fails a check).
        `tools`: OpenAI function-calling tool definitions (booking-agent, PY-07); the model may return `tool_calls`
        instead of `text` — the caller executes the tool and calls again with the result appended to `messages`
        (role="tool")."""
        cfg = self._tasks[task]
        result = RouterResult(answered=False, final_fallback=cfg.final_fallback)
        if self.budget_exhausted():
            result.budget_exceeded = True
            return result
        known = {k: list(v) for k, v in (known_pii or {}).items()}
        for level, step in enumerate(cfg.chain):
            if level < start_level:
                continue
            key = f"{step.provider}/{step.model}"
            provider = self._providers[step.provider]
            if not self._breaker.allow(key):
                result.errors.append(f"{step.provider}:circuit-open")
                continue
            sent, redactions = self._outgoing(step, provider, messages, known)
            try:
                c: Completion = provider.complete(
                    step.model, sent, cfg.max_output_tokens, cfg.timeout_seconds, tools=tools
                )
            except LlmError as exc:
                self._breaker.failure(key)
                result.errors.append(f"{step.provider}:{exc.kind}")
                self._emit(step, exc.kind, 0, 0)
                continue
            text = c.text
            for r in redactions:
                text = r.restore(text)
            self._succeeded(result, step, level, c, text)
            return result
        return result

    def stream(
        self,
        task: str,
        messages: Sequence[ChatMessage],
        known_pii: Mapping[str, Iterable[str]] | None = None,
        start_level: int = 0,
    ) -> RouterStream:
        """Like `complete` (no tools), delivering the answer as it is generated: `StreamDelta`* with a
        `StreamReset` whenever a step fails after producing text, then exactly one `StreamEnd`.
        A provider without `stream` is called with `complete` and its answer arrives as one delta."""
        result = RouterResult(answered=False, final_fallback=self._tasks[task].final_fallback)
        events = self._stream(task, messages, known_pii, start_level, result)
        return RouterStream(events, result, events.close)

    def _stream(
        self,
        task: str,
        messages: Sequence[ChatMessage],
        known_pii: Mapping[str, Iterable[str]] | None,
        start_level: int,
        result: RouterResult,
    ) -> Generator[StreamEvent]:
        cfg = self._tasks[task]
        if self.budget_exhausted():
            result.budget_exceeded = True
            yield StreamEnd(result)
            return
        known = {k: list(v) for k, v in (known_pii or {}).items()}
        for level, step in enumerate(cfg.chain):
            if level < start_level:
                continue
            key = f"{step.provider}/{step.model}"
            provider = self._providers[step.provider]
            if not self._breaker.allow(key):
                result.errors.append(f"{step.provider}:circuit-open")
                continue
            sent, redactions = self._outgoing(step, provider, messages, known)
            restorer = StreamRestorer(redactions)
            started = self._clock()
            raw: list[str] = []  # as sent by the provider (still redacted): used only to estimate tokens
            shown = False
            completion: Completion | None = None
            source: Iterator[str | Completion]
            if isinstance(provider, StreamingProvider):
                source = provider.stream(step.model, sent, cfg.max_output_tokens, cfg.timeout_seconds)
            else:
                source = _single_delta(provider, step.model, sent, cfg)
            try:
                for item in source:
                    if isinstance(item, Completion):
                        completion = item
                        break
                    if not raw and self._on_first_token is not None:
                        self._on_first_token(step.provider, step.model, self._clock() - started)
                    raw.append(item)
                    text = restorer.feed(item)
                    if text:
                        shown = True
                        yield StreamDelta(text, step.provider, step.model)
                if completion is None:
                    raise LlmError("invalid-response", step.provider)
            except LlmError as exc:
                self._breaker.failure(key)
                result.errors.append(f"{step.provider}:{exc.kind}")
                self._emit(step, exc.kind, 0, 0)
                if shown:
                    yield StreamReset(f"{step.provider}:{exc.kind}")
                continue
            except GeneratorExit:
                # The caller stopped listening mid-answer. The provider worked and the tokens were consumed, so
                # they count against the budget; the provider reported no usage yet, so the counts are estimates.
                _close(source)
                tokens_in = sum(estimate_tokens(m.content) for m in sent)
                tokens_out = estimate_tokens("".join(raw))
                self._breaker.success(key)
                self._emit(step, "aborted", tokens_in, tokens_out)
                if self._budget is not None:
                    self._budget.add(tokens_in + tokens_out)
                result.provider, result.model, result.fallback_level = step.provider, step.model, level
                result.tokens_in, result.tokens_out = tokens_in, tokens_out
                result.latency_ms = int((self._clock() - started) * 1000)
                result.errors.append(f"{step.provider}:aborted")
                raise
            tail = restorer.flush()
            if tail:
                yield StreamDelta(tail, step.provider, step.model)
            full = completion.text
            for r in redactions:
                full = r.restore(full)
            self._succeeded(result, step, level, completion, full)
            yield StreamEnd(result)
            return
        yield StreamEnd(result)

    def _outgoing(
        self, step: ChainStep, provider: Provider, messages: Sequence[ChatMessage], known: Mapping[str, list[str]]
    ) -> tuple[list[ChatMessage], list[Redaction]]:
        """The messages as this step may see them, and the redactions needed to restore its answer."""
        if not (step.redact_pii or provider.external):
            return list(messages), []
        # role="tool": tool-result JSON produced by the backend (slot/appointment ids...), not natural text from
        # the user; redacting PII here would corrupt ids the model must read back intact in the next turn
        # (booking-agent).
        redactions = [redact(m.content, known) if m.role != "tool" else Redaction(m.content, {}) for m in messages]
        sent = [
            ChatMessage(m.role, r.text, tool_call_id=m.tool_call_id, tool_calls=m.tool_calls)
            for m, r in zip(messages, redactions, strict=True)
        ]
        return sent, redactions

    def _succeeded(self, result: RouterResult, step: ChainStep, level: int, c: Completion, text: str) -> None:
        self._breaker.success(f"{step.provider}/{step.model}")
        self._emit(step, "success", c.tokens_in, c.tokens_out)
        if self._budget is not None:
            self._budget.add(c.tokens_in + c.tokens_out)
        result.answered = True
        result.text = text
        result.provider = step.provider
        result.model = c.model
        result.fallback_level = level
        result.tokens_in, result.tokens_out, result.latency_ms = c.tokens_in, c.tokens_out, c.latency_ms
        result.tool_calls = c.tool_calls

    def budget_exhausted(self) -> bool:
        """True when the daily token budget is used up: no model is called until the next day (UTC)."""
        return self._budget is not None and self._budget.exhausted()

    def _emit(self, step: ChainStep, outcome: str, tin: int, tout: int) -> None:
        if self._on_call is not None:
            self._on_call(step.provider, step.model, outcome, tin, tout)

    def chain_length(self, task: str) -> int:
        return len(self._tasks[task].chain)


def _single_delta(
    provider: Provider, model: str, sent: Sequence[ChatMessage], cfg: TaskConfig
) -> Iterator[str | Completion]:
    c = provider.complete(model, sent, cfg.max_output_tokens, cfg.timeout_seconds)
    if c.text:
        yield c.text
    yield c


def _close(source: Iterator[object]) -> None:
    close = getattr(source, "close", None)
    if callable(close):
        close()
