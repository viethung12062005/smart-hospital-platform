"""Theo dõi token, chi phí, fallback (AI-13, PY-09; 12 §6 AI10, NFR-C2).

- Mỗi lời gọi LLM ghi một dòng `usage` (ngày, tác vụ, provider, model, token, chi phí, fallbackLevel, có trả lời).
  Không có nội dung câu hỏi hay câu trả lời.
- `MongoTokenBudget`: hạn mức token/ngày dùng chung cho mọi replica (bộ đếm `$inc` nguyên tử), cảnh báo ở 80%.
"""

from collections.abc import Callable
from datetime import UTC, date, datetime, timedelta
from typing import Any

from prometheus_client import Counter, Gauge
from pymongo import ASCENDING
from pymongo.database import Database

from shp_platform.llm.router import RouterResult, TokenBudget

LLM_CALLS = Counter(
    "shp_ai_llm_calls_total", "Lời gọi LLM theo provider/model/kết quả", ["provider", "model", "outcome"]
)
LLM_TOKENS = Counter("shp_ai_llm_tokens_total", "Token theo provider/model/chiều", ["provider", "model", "direction"])
BUDGET_USED = Gauge("shp_ai_budget_used_ratio", "Tỉ lệ hạn mức token trong ngày đã dùng")
BUDGET_ALERT = Gauge("shp_ai_budget_alert", "1 khi đã dùng ≥ 80% hạn mức ngày")
ALERT_AT = 0.8
Doc = dict[str, Any]


def on_call(provider: str, model: str, outcome: str, tokens_in: int, tokens_out: int) -> None:
    LLM_CALLS.labels(provider=provider, model=model, outcome=outcome).inc()
    if tokens_in or tokens_out:
        LLM_TOKENS.labels(provider=provider, model=model, direction="in").inc(tokens_in)
        LLM_TOKENS.labels(provider=provider, model=model, direction="out").inc(tokens_out)


class MongoTokenBudget(TokenBudget):
    def __init__(self, db: Database[Doc], daily_limit: int, today: Callable[[], str] | None = None) -> None:
        super().__init__(daily_limit, today)
        self._db = db

    def used(self) -> int:
        doc = self._db.ai_budget.find_one({"_id": self._today()})
        return int(doc["tokens"]) if doc else 0

    def add(self, tokens: int) -> None:
        doc = self._db.ai_budget.find_one_and_update(
            {"_id": self._today()}, {"$inc": {"tokens": tokens}}, upsert=True, return_document=True
        )
        total = int(doc["tokens"]) if doc else tokens
        ratio = total / self.daily_limit if self.daily_limit else 1.0
        BUDGET_USED.set(round(ratio, 4))
        BUDGET_ALERT.set(int(ratio >= ALERT_AT))


def ensure_indexes(db: Database[Doc]) -> None:
    db.usage.create_index([("day", ASCENDING), ("task", ASCENDING)])
    db.usage.create_index([("at", ASCENDING)], expireAfterSeconds=400 * 24 * 3600)


def record(db: Database[Doc], task: str, r: RouterResult, cost_usd: float, actor_role: str) -> None:
    now = datetime.now(UTC)
    db.usage.insert_one(
        {
            "at": now,
            "day": now.date().isoformat(),
            "task": task,
            "provider": r.provider or "none",
            "model": r.model or "none",
            "inputTokens": r.tokens_in,
            "outputTokens": r.tokens_out,
            "costUsd": cost_usd,
            "fallbackLevel": r.fallback_level,
            "answered": r.answered,
            "budgetExceeded": r.budget_exceeded,
            "errors": r.errors,
            "actorRole": actor_role,
        }
    )


def summary(db: Database[Doc], start: date, end: date, group_by: str) -> list[Doc]:
    key = {"task": "$task", "model": {"$concat": ["$provider", "/", "$model"]}, "day": "$day"}[group_by]
    rows = db.usage.aggregate(
        [
            {"$match": {"day": {"$gte": start.isoformat(), "$lte": end.isoformat()}}},
            {
                "$group": {
                    "_id": key,
                    "requests": {"$sum": 1},
                    "inputTokens": {"$sum": "$inputTokens"},
                    "outputTokens": {"$sum": "$outputTokens"},
                    "costUsd": {"$sum": "$costUsd"},
                    "fallbacks": {"$sum": {"$cond": [{"$gt": ["$fallbackLevel", 0]}, 1, 0]}},
                }
            },
            {"$sort": {"_id": 1}},
        ]
    )
    return [
        {
            "key": r["_id"],
            "requests": r["requests"],
            "inputTokens": r["inputTokens"],
            "outputTokens": r["outputTokens"],
            "costUsd": round(r["costUsd"], 4),
            "fallbackRate": round(r["fallbacks"] / r["requests"], 4) if r["requests"] else 0.0,
        }
        for r in rows
    ]


def default_range(today: date | None = None) -> tuple[date, date]:
    t = today or datetime.now(UTC).date()
    return t - timedelta(days=30), t
