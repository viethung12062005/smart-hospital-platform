"""Inbox, outbox and relay on MongoDB for Python services (PY-03; same as org.shp.data.mongo).

- `handle_once`: one transaction covers the inbox marker + handler (the handler's data and outbox use the same session).
  Handler failure → roll back everything, including the inbox marker. Already-processed event → skip.
- `add_to_outbox`: write the event in the SAME transaction as the data change.
- `relay_once`: claim each event with find_one_and_update (30-second lease) so multiple replicas do not publish
  duplicates; on bus failure record `attempts`/`lastError`, release the lock and stop the round (preserving order).
Requires a MongoDB replica set (local: already enabled by `make -C dev up`; cloud: Cosmos DB vCore).
"""

import uuid
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from pymongo import ASCENDING, MongoClient, ReturnDocument
from pymongo.client_session import ClientSession
from pymongo.collection import Collection
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


def _ensure_index(collection: Collection[Doc], keys: list[tuple[str, int]], name: str, **options: Any) -> None:
    """Create indexes under fixed names, even when the same key already exists under another name.

    MongoDB reports error 85 `IndexOptionsConflict` when the same index key already exists under another name, and
    this key is declared in three places: `MongoMessaging` (Java), `contracts/db/mongo-notification-ml.js` (the
    quickstart runs before the service) and here. Old dev DBs still have indexes named by pymongo itself
    (`processedAt_1`) from before explicit naming — both directions must start, so respect an existing index when
    the key and options match.
    """
    for existing, info in collection.index_information().items():
        if list(info["key"]) == keys and existing != name and all(info.get(k) == v for k, v in options.items()):
            return
    collection.create_index(keys, name=name, **options)


def ensure_messaging_indexes(db: Database[Doc]) -> None:
    """Idempotent: 14-day inbox TTL, outbox waiting to publish in order (names/keys match Java and contracts/db)."""
    _ensure_index(
        db[INBOX], [("processedAt", ASCENDING)], "ttl_inbox_14d", expireAfterSeconds=int(INBOX_TTL.total_seconds())
    )
    _ensure_index(
        db[OUTBOX], [("occurredAt", ASCENDING)], "ix_outbox_unpublished", partialFilterExpression={"publishedAt": None}
    )


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
    """Return False if the event was already processed."""
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
        raise RuntimeError("add_to_outbox must run in a transaction with the data change")
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
        super().__init__(f"bus failed after {sent} events")


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
