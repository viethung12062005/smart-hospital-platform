"""Idempotency-Key trên MongoDB cho service Python (giống SHR-12 bên Java; 07 §4).

- Khóa = (người gọi, Idempotency-Key). Lần đầu giành khóa rồi xử lý; cùng khóa + cùng thân yêu cầu → trả lại đúng
  phản hồi đã lưu; cùng khóa + thân khác → 422 `idempotency-key-reused`; đang xử lý → 409.
- Lưu 24 giờ (TTL). Thân yêu cầu chỉ lưu băm, không lưu nội dung.
"""

import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Any

from pymongo import ASCENDING
from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

from shp_platform.errors import ShpError

COLLECTION = "idempotency"
TTL = timedelta(hours=24)
KEY_MAX = 128


def ensure_indexes(db: Database[dict[str, Any]]) -> None:
    db[COLLECTION].create_index([("createdAt", ASCENDING)], expireAfterSeconds=int(TTL.total_seconds()))


def request_hash(body: Any) -> str:
    return hashlib.sha256(json.dumps(body, sort_keys=True, default=str, ensure_ascii=False).encode()).hexdigest()


class Idempotency:
    def __init__(self, db: Database[dict[str, Any]]) -> None:
        self._c = db[COLLECTION]

    @staticmethod
    def _id(subject: str, scope: str, key: str) -> str:
        if not key or len(key) > KEY_MAX:
            raise ShpError("idempotency-key-required")
        return hashlib.sha256(f"{subject}|{scope}|{key}".encode()).hexdigest()

    def begin(self, subject: str, scope: str, key: str, body_hash: str) -> dict[str, Any] | None:
        """Trả phản hồi đã lưu (replay) hoặc None nếu đã giành khóa để xử lý lần đầu."""
        _id = self._id(subject, scope, key)
        try:
            self._c.insert_one({"_id": _id, "hash": body_hash, "state": "processing", "createdAt": datetime.now(UTC)})
            return None
        except DuplicateKeyError:
            doc = self._c.find_one({"_id": _id})
        if doc is None:
            return self.begin(subject, scope, key, body_hash)
        if doc["hash"] != body_hash:
            raise ShpError("idempotency-key-reused")
        if doc["state"] != "done":
            raise ShpError("invalid-state-transition", "Yêu cầu cùng Idempotency-Key đang được xử lý.")
        stored: dict[str, Any] = doc["response"]
        return stored

    def finish(self, subject: str, scope: str, key: str, status: int, body: Any) -> None:
        self._c.update_one(
            {"_id": self._id(subject, scope, key)},
            {"$set": {"state": "done", "response": {"status": status, "body": body}}},
        )

    def abandon(self, subject: str, scope: str, key: str) -> None:
        """Lỗi trong lúc xử lý: nhả khóa để người dùng thử lại với cùng khóa."""
        self._c.delete_one({"_id": self._id(subject, scope, key), "state": "processing"})
