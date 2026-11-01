"""Inbox, outbox và relay trên MongoDB cho service Python (PY-03; giống org.shp.data.mongo).

- `handle_once`: một transaction gồm dấu inbox + handler (dữ liệu và outbox của handler dùng cùng session).
  Handler lỗi → rollback toàn bộ, kể cả dấu inbox. Event đã xử lý → bỏ qua.
- `add_to_outbox`: ghi event trong CÙNG transaction với thay đổi dữ liệu.
- `relay_once`: giành từng event bằng find_one_and_update (lease 30 giây) nên nhiều replica không phát trùng; bus lỗi
  thì ghi `attempts`/`lastError`, nhả khóa và dừng lượt (giữ thứ tự).
Cần MongoDB replica set (local: `make -C dev up` đã bật; cloud: Cosmos DB vCore).
"""

import uuid
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from pymongo import ASCENDING, MongoClient, ReturnDocument
from pymongo.client_session import ClientSession
from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

INBOX = "inbox"
OUTBOX = "outbox"
INBOX_TTL = timedelta(days=14)
LEASE = timedelta(seconds=30)

Doc = dict[str, Any]


class Publisher(Protocol):
    def publish(self, event: dict[str, Any]) -> None: ...


def now() -> datetime:
    return datetime.now(UTC)


def ensure_messaging_indexes(db: Database[Doc]) -> None:
    """Idempotent: TTL inbox 14 ngày, outbox chờ phát theo thứ tự."""
    db[INBOX].create_index([("processedAt", ASCENDING)], expireAfterSeconds=int(INBOX_TTL.total_seconds()))
    db[OUTBOX].create_index([("publishedAt", ASCENDING), ("occurredAt", ASCENDING), ("_id", ASCENDING)])


def in_transaction[T](client: MongoClient[Doc], fn: Callable[[ClientSession], T]) -> T:
    with client.start_session() as session:
        return session.with_transaction(fn)


def handle_once(
    client: MongoClient[Doc],
    db: Database[Doc],
    consumer: str,
    event: dict[str, Any],
    handler: Callable[[ClientSession], None],
) -> bool:
    """Trả False nếu event đã được xử lý trước đó."""
    key = f"{consumer}|{event['id']}"

    def work(session: ClientSession) -> bool:
        if db[INBOX].find_one({"_id": key}, session=session) is not None:
            return False
        db[INBOX].insert_one({"_id": key, "processedAt": now()}, session=session)
        handler(session)
        return True

    try:
        return in_transaction(client, work)
    except DuplicateKeyError:
        if db[INBOX].find_one({"_id": key}) is not None:
            return False
        raise


def add_to_outbox(db: Database[Doc], session: ClientSession, event: dict[str, Any]) -> None:
    if not session.in_transaction:
        raise RuntimeError("add_to_outbox phải chạy trong transaction cùng thay đổi dữ liệu")
    db[OUTBOX].insert_one(
        {
            "_id": event["id"],
            "eventType": event["type"],
            "subject": event["subject"],
            "payload": event,
            "occurredAt": datetime.fromisoformat(event["time"].replace("Z", "+00:00")),
            "publishedAt": None,
            "attempts": 0,
        },
        session=session,
    )


class OutboxPublishError(RuntimeError):
    def __init__(self, sent: int) -> None:
        self.sent = sent
        super().__init__(f"bus lỗi sau {sent} event")


def relay_once(db: Database[Doc], publisher: Publisher, batch_size: int = 100, instance: str | None = None) -> int:
    me = instance or str(uuid.uuid4())
    sent = 0
    while sent < batch_size:
        t = now()
        claimed = db[OUTBOX].find_one_and_update(
            {"publishedAt": None, "$or": [{"lockedUntil": None}, {"lockedUntil": {"$lt": t}}]},
            {"$set": {"lockedUntil": t + LEASE, "lockedBy": me}},
            sort=[("occurredAt", ASCENDING), ("_id", ASCENDING)],
            return_document=ReturnDocument.AFTER,
        )
        if claimed is None:
            return sent
        try:
            publisher.publish(claimed["payload"])
        except Exception as exc:
            err = f"{type(exc).__name__}: {exc}"[:500]
            db[OUTBOX].update_one(
                {"_id": claimed["_id"]},
                {"$inc": {"attempts": 1}, "$set": {"lastError": err}, "$unset": {"lockedUntil": "", "lockedBy": ""}},
            )
            raise OutboxPublishError(sent) from exc
        db[OUTBOX].update_one(
            {"_id": claimed["_id"], "lockedBy": me},
            {"$set": {"publishedAt": now()}, "$unset": {"lockedUntil": "", "lockedBy": ""}},
        )
        sent += 1
    return sent
