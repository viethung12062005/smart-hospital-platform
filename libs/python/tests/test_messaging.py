import json
import threading
import time
import uuid
from datetime import timedelta
from pathlib import Path
from typing import Any

import pytest
from pymongo.client_session import ClientSession

from shp_platform import events, mongo
from shp_platform.bus import RabbitBus
from shp_platform.events import Outcome
from shp_platform.testing import fresh_db

ROOT = Path(__file__).resolve().parents[3]


def booked() -> dict[str, Any]:
    data = json.loads((ROOT / "contracts/events/scheduling/appointment-booked.v1.example.json").read_text("utf-8"))
    return events.create("scheduling", "shp.scheduling.appointment-booked.v1", f"Appointment/{uuid.uuid4()}", data)


class FakePublisher:
    def __init__(self, fail_on: int | None = None) -> None:
        self.sent: list[dict[str, Any]] = []
        self.fail_on = fail_on

    def publish(self, event: dict[str, Any]) -> None:
        if self.fail_on is not None and len(self.sent) == self.fail_on:
            raise ConnectionError("bus down")
        self.sent.append(event)


def test_handle_once_is_atomic_and_idempotent(mongo_uri: str) -> None:
    client, db = fresh_db(mongo_uri, "t_inbox")
    mongo.ensure_messaging_indexes(db)
    evt = booked()

    def write(session: ClientSession) -> None:
        db.facts.insert_one({"_id": evt["id"]}, session=session)
        mongo.add_to_outbox(
            db,
            session,
            events.create(
                "ml",
                "shp.ml.no-show-scored.v1",
                evt["subject"],
                {
                    "appointmentId": str(uuid.uuid4()),
                    "patientId": str(uuid.uuid4()),
                    "start": "2026-12-03T01:15:00Z",
                    "score": 0.4,
                    "threshold": 0.3,
                    "highRisk": True,
                    "modelName": "m",
                    "modelVersion": "1",
                    "scoredAt": "2026-11-20T02:15:03Z",
                },
            ),
        )

    assert mongo.handle_once(client, db, "ml", evt, write) is True
    assert mongo.handle_once(client, db, "ml", evt, write) is False
    assert db.facts.count_documents({}) == 1 and db.outbox.count_documents({}) == 1

    other = booked()

    def boom(session: ClientSession) -> None:
        db.facts.insert_one({"_id": other["id"]}, session=session)
        raise RuntimeError("handler failed")

    with pytest.raises(RuntimeError):
        mongo.handle_once(client, db, "ml", other, boom)
    # full rollback: no data, no inbox marker -> the next delivery can process it again
    assert db.facts.count_documents({"_id": other["id"]}) == 0
    assert db.inbox.count_documents({"_id": f"ml|{other['id']}"}) == 0


def test_ensure_messaging_indexes_reuses_contract_indexes(mongo_uri: str) -> None:
    """The quickstart runs contracts/db/mongo-notification-ml.js before the service: same name + key, so no conflict."""
    client, db = fresh_db(mongo_uri, "t_indexes")
    db.inbox.create_index([("processedAt", 1)], name="ttl_inbox_14d", expireAfterSeconds=14 * 24 * 3600)
    db.outbox.create_index(
        [("occurredAt", 1)], name="ix_outbox_unpublished", partialFilterExpression={"publishedAt": None}
    )

    mongo.ensure_messaging_indexes(db)
    mongo.ensure_messaging_indexes(db)  # running again is still idempotent

    assert {i["name"] for i in db.inbox.list_indexes()} == {"_id_", "ttl_inbox_14d"}
    assert {i["name"] for i in db.outbox.list_indexes()} == {"_id_", "ix_outbox_unpublished"}


def test_ensure_messaging_indexes_keeps_indexes_created_before_naming(mongo_uri: str) -> None:
    """An old dev DB (e.g. `ai`, untouched by the init script) already has a pymongo-named index."""
    client, db = fresh_db(mongo_uri, "t_legacy_indexes")
    db.inbox.create_index([("processedAt", 1)], expireAfterSeconds=14 * 24 * 3600)
    db.outbox.create_index([("publishedAt", 1), ("occurredAt", 1), ("_id", 1)])

    mongo.ensure_messaging_indexes(db)
    mongo.ensure_messaging_indexes(db)

    inbox = {i["name"]: i for i in db.inbox.list_indexes()}
    assert set(inbox) == {"_id_", "processedAt_1"}  # no duplicate-key index is created
    assert inbox["processedAt_1"]["expireAfterSeconds"] == 14 * 24 * 3600
    # the old outbox has a different key -> add the standard index, no conflict
    assert "ix_outbox_unpublished" in {i["name"] for i in db.outbox.list_indexes()}


def test_outbox_requires_transaction(mongo_uri: str) -> None:
    client, db = fresh_db(mongo_uri, "t_outbox_tx")
    with client.start_session() as s, pytest.raises(RuntimeError):
        mongo.add_to_outbox(db, s, booked())


def test_relay_publishes_in_order_and_stops_on_bus_error(mongo_uri: str) -> None:
    client, db = fresh_db(mongo_uri, "t_relay")
    evts = [booked() for _ in range(3)]
    for e in evts:

        def add(s: ClientSession, e: dict[str, Any] = e) -> None:
            mongo.add_to_outbox(db, s, e)

        mongo.in_transaction(client, add)
    # Order is guaranteed only per subject (13-events §1); 3 events with different subjects created in the same ms
    # may have arbitrary UUIDv7 order, so derive the expected order from the relay's actual sort key (occurredAt, _id).
    order = [d["_id"] for d in db.outbox.find({}, {"_id": 1}).sort([("occurredAt", 1), ("_id", 1)])]
    assert sorted(order) == sorted(e["id"] for e in evts)
    flaky = FakePublisher(fail_on=1)
    with pytest.raises(mongo.OutboxPublishError) as err:
        mongo.relay_once(db, flaky)
    assert err.value.sent == 1
    assert [e["id"] for e in flaky.sent] == [order[0]]
    failed = db.outbox.find_one({"_id": order[1]})
    assert failed["attempts"] == 1 and "lockedBy" not in failed and failed["publishedAt"] is None
    ok = FakePublisher()
    assert mongo.relay_once(db, ok) == 2
    assert [e["id"] for e in ok.sent] == [order[1], order[2]]
    assert mongo.relay_once(db, ok) == 0


def test_relay_reclaims_expired_lease(mongo_uri: str) -> None:
    client, db = fresh_db(mongo_uri, "t_lease")
    e = booked()
    mongo.in_transaction(client, lambda s: mongo.add_to_outbox(db, s, e))
    db.outbox.update_one(
        {"_id": e["id"]}, {"$set": {"lockedBy": "dead", "lockedUntil": mongo.now() + timedelta(hours=1)}}
    )
    assert mongo.relay_once(db, FakePublisher()) == 0  # another replica holds it
    db.outbox.update_one({"_id": e["id"]}, {"$set": {"lockedUntil": mongo.now() - timedelta(seconds=1)}})
    assert mongo.relay_once(db, FakePublisher()) == 1


def test_rabbit_roundtrip_retry_and_dead_letter(rabbit_url: str) -> None:
    bus = RabbitBus(rabbit_url)
    consumer = f"test-{uuid.uuid4().hex[:8]}"
    seen: list[tuple[str, int]] = []
    stop = threading.Event()
    poison = booked()

    def handler(body: bytes, attempt: int) -> Outcome:
        evt = json.loads(body)
        seen.append((evt["id"], attempt))
        return Outcome.RETRY if evt["id"] == poison["id"] else Outcome.PROCESSED

    t = threading.Thread(target=bus.consume, args=(consumer, ["shp.scheduling.appointment-booked.v1"], handler, stop))
    t.start()
    time.sleep(2)  # queue + binding declared
    good = booked()
    bus.publish(good)
    bus.publish(poison)
    deadline = time.time() + 30
    while time.time() < deadline and sum(1 for i, _ in seen if i == poison["id"]) < 5:
        time.sleep(0.2)
    stop.set()
    t.join(10)
    assert (good["id"], 1) in seen
    attempts = [a for i, a in seen if i == poison["id"]]
    assert attempts[:5] == [1, 2, 3, 4, 5]
    import pika

    ch = pika.BlockingConnection(pika.URLParameters(rabbit_url)).channel()
    deadline = time.time() + 10
    dead = None
    while time.time() < deadline and dead is None:
        _, _, dead = ch.basic_get(f"{consumer}.dlq", auto_ack=True)
        time.sleep(0.2)
    assert dead is not None and json.loads(dead)["id"] == poison["id"]
    bus.close()
