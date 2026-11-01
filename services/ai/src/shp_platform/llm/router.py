"""Router chuỗi model theo tác vụ, circuit breaker, hạn mức token/ngày (PY-08, PY-09, 06-data-ai §4).

- Thử lần lượt các bước trong chuỗi; lỗi, mạch mở hoặc hết hạn mức thì chuyển bước kế. `fallback_level` = chỉ số bước
  đã trả lời (0 = model chính). Hết chuỗi thì trả `answered=False` cùng `final_fallback` để service tự xử lý
  (retrieval-only, structured-timeline, human-review, booking-form).
- Provider ngoài Azure (`external`) luôn nhận văn bản đã che PII, dù cấu hình ghi `redact_pii: false`.
- Mạch mở sau `failure_threshold` lỗi trong `window_seconds`, đóng thử lại sau `open_seconds`.
- Hết hạn mức token trong ngày: không gọi model nào nữa, service trả ai-budget-exceeded.
"""

import time
from collections import defaultdict, deque
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime

from shp_platform.llm.provider import ChatMessage, Completion, LlmError, Provider
from shp_platform.pii import redact


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
    errors: list[str] = field(default_factory=list)  # "deepseek:timeout"; không chứa nội dung


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
            del self._opened_at[key]  # nửa mở: cho một lần thử, lỗi tiếp thì mở lại ngay
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
    """Hạn mức token/ngày (UTC) cho cả service. Bản nhiều replica cần bộ đếm chung (Redis) - truyền `store` khác."""

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
    ) -> None:
        """`on_call(provider, model, outcome, tokens_in, tokens_out)` để xuất metric theo model (Prometheus)."""
        self._providers = {p.name: p for p in providers}
        self._tasks = dict(tasks)
        self._breaker = breaker or CircuitBreaker()
        self._budget = budget
        self._on_call = on_call
        for name, cfg in self._tasks.items():
            for step in cfg.chain:
                if step.provider not in self._providers:
                    raise ValueError(f"tác vụ {name}: provider chưa khai báo {step.provider}")

    def complete(
        self, task: str, messages: Sequence[ChatMessage], known_pii: Mapping[str, Iterable[str]] | None = None
    ) -> RouterResult:
        cfg = self._tasks[task]
        result = RouterResult(answered=False, final_fallback=cfg.final_fallback)
        if self._budget is not None and self._budget.exhausted():
            result.budget_exceeded = True
            return result
        known = {k: list(v) for k, v in (known_pii or {}).items()}
        for level, step in enumerate(cfg.chain):
            key = f"{step.provider}/{step.model}"
            provider = self._providers[step.provider]
            if not self._breaker.allow(key):
                result.errors.append(f"{step.provider}:circuit-open")
                continue
            must_redact = step.redact_pii or provider.external
            redactions = [redact(m.content, known) for m in messages] if must_redact else []
            sent = (
                [ChatMessage(m.role, r.text) for m, r in zip(messages, redactions, strict=True)]
                if must_redact
                else list(messages)
            )
            try:
                c: Completion = provider.complete(step.model, sent, cfg.max_output_tokens, cfg.timeout_seconds)
            except LlmError as exc:
                self._breaker.failure(key)
                result.errors.append(f"{step.provider}:{exc.kind}")
                self._emit(step, exc.kind, 0, 0)
                continue
            self._breaker.success(key)
            self._emit(step, "success", c.tokens_in, c.tokens_out)
            if self._budget is not None:
                self._budget.add(c.tokens_in + c.tokens_out)
            text = c.text
            for r in redactions:
                text = r.restore(text)
            result.answered = True
            result.text = text
            result.provider = step.provider
            result.model = c.model
            result.fallback_level = level
            result.tokens_in, result.tokens_out, result.latency_ms = c.tokens_in, c.tokens_out, c.latency_ms
            return result
        return result

    def _emit(self, step: ChainStep, outcome: str, tin: int, tout: int) -> None:
        if self._on_call is not None:
            self._on_call(step.provider, step.model, outcome, tin, tout)
