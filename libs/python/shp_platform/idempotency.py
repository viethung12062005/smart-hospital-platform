"""Idempotency-Key on MongoDB for Python services (same as SHR-12 in Java; 07 §4).

- Key = (caller, Idempotency-Key). The first request claims the key then processes; same key + same request body →
  return the exact stored response; same key + different body → 422 `idempotency-key-reused`; in progress → 409.
- Stored for 24 hours (TTL). Only a hash of the request body is stored, never its content.
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
        """Return the stored response (replay), or None if the key was claimed for first-time processing."""
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
        """Processing failed: release the key so the user can retry with the same key."""
        self._c.delete_one({"_id": self._id(subject, scope, key), "state": "processing"})
