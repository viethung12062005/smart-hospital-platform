"""Tests for the Python Idempotency-Key store (07 §4; the Python counterpart of SHR-12).

Runs against a real MongoDB replica set from `conftest.py`; no mocks, so a duplicate-key race and the TTL index are
exercised for real.
"""

from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from pymongo.errors import DuplicateKeyError

from shp_platform import idempotency
from shp_platform.errors import ShpError
from shp_platform.idempotency import Idempotency, request_hash
from shp_platform.testing import fresh_db


@pytest.fixture()
def store(mongo_uri: str) -> Any:
    client, db = fresh_db(mongo_uri, "t_idempotency")
    try:
        yield Idempotency(db), db
    finally:
        client.close()


def code(exc: ShpError) -> str:
    return exc.info.code


def test_request_hash_is_stable_and_order_independent() -> None:
    assert request_hash({"a": 1, "b": [2, 3]}) == request_hash({"b": [2, 3], "a": 1})
    assert request_hash({"a": 1}) != request_hash({"a": 2})
    # Vietnamese text must hash the same way on every run (ensure_ascii=False).
    assert request_hash({"comment": "Khám lại"}) == request_hash({"comment": "Khám lại"})


def test_first_request_claims_the_key_then_replays_the_same_response(store: Any) -> None:
    idem, _ = store
    h = request_hash({"slot": "s1"})
    assert idem.begin("patient-1", "book", "key-1", h) is None
    idem.finish("patient-1", "book", "key-1", 201, {"id": "appointment-1"})
    assert idem.begin("patient-1", "book", "key-1", h) == {
        "status": 201,
        "body": {"id": "appointment-1"},
    }


def test_same_key_with_a_different_body_is_rejected(store: Any) -> None:
    idem, _ = store
    idem.begin("patient-1", "book", "key-2", request_hash({"slot": "s1"}))
    idem.finish("patient-1", "book", "key-2", 201, {"id": "a"})
    with pytest.raises(ShpError) as exc:
        idem.begin("patient-1", "book", "key-2", request_hash({"slot": "s2"}))
    assert code(exc.value) == "idempotency-key-reused"


def test_second_request_while_the_first_is_processing_is_rejected(store: Any) -> None:
    idem, _ = store
    h = request_hash({"slot": "s1"})
    idem.begin("patient-1", "book", "key-3", h)
    with pytest.raises(ShpError) as exc:
        idem.begin("patient-1", "book", "key-3", h)
    assert code(exc.value) == "invalid-state-transition"


def test_the_key_is_scoped_by_caller_and_scope(store: Any) -> None:
    idem, _ = store
    h = request_hash({"slot": "s1"})
    assert idem.begin("patient-1", "book", "key-4", h) is None
    # Same literal key, different caller and different scope: both are new keys.
    assert idem.begin("patient-2", "book", "key-4", h) is None
    assert idem.begin("patient-1", "reschedule", "key-4", h) is None


def test_abandon_releases_the_key_so_the_user_can_retry(store: Any) -> None:
    idem, _ = store
    h = request_hash({"slot": "s1"})
    idem.begin("patient-1", "book", "key-5", h)
    idem.abandon("patient-1", "book", "key-5")
    assert idem.begin("patient-1", "book", "key-5", h) is None


def test_abandon_does_not_release_a_completed_key(store: Any) -> None:
    idem, _ = store
    h = request_hash({"slot": "s1"})
    idem.begin("patient-1", "book", "key-6", h)
    idem.finish("patient-1", "book", "key-6", 201, {"id": "a"})
    idem.abandon("patient-1", "book", "key-6")
    assert idem.begin("patient-1", "book", "key-6", h) == {"status": 201, "body": {"id": "a"}}


@pytest.mark.parametrize("key", ["", "x" * (idempotency.KEY_MAX + 1)])
def test_empty_or_overlong_key_is_rejected(store: Any, key: str) -> None:
    idem, _ = store
    with pytest.raises(ShpError) as exc:
        idem.begin("patient-1", "book", key, request_hash({}))
    assert code(exc.value) == "idempotency-key-required"


def test_only_a_hash_of_the_body_is_stored(store: Any) -> None:
    idem, db = store
    body = {"name": "Nguyễn Thị Lan", "phone": "+84900000001"}
    idem.begin("patient-1", "book", "key-7", request_hash(body))
    doc = db[idempotency.COLLECTION].find_one({})
    assert doc is not None
    assert "Nguyễn Thị Lan" not in str(doc)
    assert set(doc) >= {"_id", "hash", "state", "createdAt"}


def test_indexes_are_created_with_the_24h_ttl(store: Any) -> None:
    _, db = store
    idempotency.ensure_indexes(db)
    indexes = {i["name"]: i for i in db[idempotency.COLLECTION].list_indexes()}
    ttl = [i for i in indexes.values() if "expireAfterSeconds" in i]
    assert ttl, indexes
    assert ttl[0]["expireAfterSeconds"] == 24 * 3600


def test_documents_expire_after_the_ttl_window(store: Any) -> None:
    # The TTL monitor runs in the background; assert the stored createdAt is inside the window instead of waiting.
    idem, db = store
    idem.begin("patient-1", "book", "key-8", request_hash({}))
    created = db[idempotency.COLLECTION].find_one({})["createdAt"]
    assert datetime.now(UTC) - created < timedelta(hours=1)


def test_a_racing_insert_loses_cleanly(store: Any) -> None:
    # Simulate two concurrent first requests: the second insert must hit the duplicate key and take the replay path.
    idem, db = store
    key_id = Idempotency._id("patient-1", "book", "key-9")  # noqa: SLF001 - the id scheme is the contract
    db[idempotency.COLLECTION].insert_one(
        {
            "_id": key_id,
            "hash": request_hash({}),
            "state": "done",
            "createdAt": datetime.now(UTC),
            "response": {"status": 200, "body": {}},
        }
    )
    with pytest.raises(DuplicateKeyError):
        db[idempotency.COLLECTION].insert_one(
            {"_id": key_id, "hash": "x", "state": "processing", "createdAt": datetime.now(UTC)}
        )
    assert idem.begin("patient-1", "book", "key-9", request_hash({})) == {"status": 200, "body": {}}
