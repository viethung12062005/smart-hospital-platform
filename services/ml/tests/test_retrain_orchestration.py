"""US-074 (MLO-04): a drift alert starts exactly one retraining run and runs never overlap (16-mlops §5).

On the cluster the guarantee has two layers: the CronJob (`concurrencyPolicy: Forbid`, shp-gitops) never starts a second
pod while one is running, and the MongoDB lock in `retrain.run_pending` stops any second process that still gets
through (manual job, scheduler glitch, node recovery). These tests exercise the second layer on a real MongoDB.
"""

import threading
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from shp_ml import retrain
from shp_ml.monitor import STATE_ID, create_request, load_state
from shp_ml.registry import FileRegistry
from shp_ml.retrain import LEASE, run_pending
from shp_ml.store import ensure_schema
from shp_platform.testing import fresh_db

NOW = datetime.now(UTC).replace(microsecond=0)


class FakeTraining:
    """Stands in for `train_candidate`: records how many runs started and the highest number running at once."""

    def __init__(self, seconds: float = 0.4) -> None:
        self.seconds = seconds
        self.started = 0
        self.running = 0
        self.max_running = 0
        self._lock = threading.Lock()

    def __call__(self, *_a: Any, **_k: Any) -> Any:
        with self._lock:
            self.started += 1
            self.running += 1
            self.max_running = max(self.max_running, self.running)
        time.sleep(self.seconds)
        with self._lock:
            self.running -= 1
        gate = SimpleNamespace(failed=lambda: [])
        return SimpleNamespace(version="7", run_id="run-1", gate=gate, metrics={"auc": 0.8})


def _request(db: Any, rid: str, created: datetime) -> None:
    db.retrain_requests.insert_one({"_id": rid, "status": "pending", "reason": "drift", "createdAt": created})


def _race(client: Any, db: Any, registry: FileRegistry, tmp_path: Path, workers: int) -> list[Any]:
    barrier = threading.Barrier(workers)
    results: list[Any] = [None] * workers

    def work(i: int) -> None:
        barrier.wait()
        results[i] = run_pending(client, db, registry, tmp_path / str(i), NOW)

    threads = [threading.Thread(target=work, args=(i,)) for i in range(workers)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)
    return results


@pytest.fixture
def registry(tmp_path: Path) -> FileRegistry:
    return FileRegistry(tmp_path / "registry")


def test_simultaneous_jobs_start_exactly_one_run(
    mongo_uri: str, registry: FileRegistry, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    client, db = fresh_db(mongo_uri, "ml_orch_one")
    ensure_schema(db)
    fake = FakeTraining()
    monkeypatch.setattr(retrain, "train_candidate", fake)
    _request(db, "alert-1", NOW)  # one simulated drift alert

    results = _race(client, db, registry, tmp_path, workers=6)  # six job pods start at the same moment

    ran = [r for r in results if r is not None]
    assert len(ran) == 1 and ran[0]["_id"] == "alert-1"
    assert fake.started == 1 and fake.max_running == 1
    req = db.retrain_requests.find_one({"_id": "alert-1"})
    assert req["status"] == "registered" and req["candidateVersion"] == "7"
    state = db.retrain_state.find_one({"_id": STATE_ID})
    assert state["running"] is False and len(state["autoRuns"]) == 1
    assert db.outbox.count_documents({"eventType": "shp.audit.recorded.v1"}) == 1  # one audit record for the one run


def test_two_requests_never_run_at_the_same_time(
    mongo_uri: str, registry: FileRegistry, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    client, db = fresh_db(mongo_uri, "ml_orch_two")
    ensure_schema(db)
    fake = FakeTraining()
    monkeypatch.setattr(retrain, "train_candidate", fake)
    _request(db, "alert-1", NOW)
    _request(db, "alert-2", NOW + timedelta(seconds=1))

    _race(client, db, registry, tmp_path, workers=4)

    assert fake.max_running == 1  # never two trainings at once
    assert 1 <= fake.started <= 2
    # the oldest request is served first; whatever is left stays pending for the next scheduled job
    assert db.retrain_requests.find_one({"_id": "alert-1"})["status"] == "registered"
    left = db.retrain_requests.count_documents({"status": "pending"})
    assert left == 2 - fake.started


def test_held_lock_blocks_a_new_run_and_keeps_the_request(
    mongo_uri: str, registry: FileRegistry, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    client, db = fresh_db(mongo_uri, "ml_orch_locked")
    ensure_schema(db)
    fake = FakeTraining(0)
    monkeypatch.setattr(retrain, "train_candidate", fake)
    db.retrain_state.insert_one({"_id": STATE_ID, "running": True, "lockedUntil": NOW + timedelta(hours=1)})
    _request(db, "alert-1", NOW)

    assert run_pending(client, db, registry, tmp_path, NOW) is None
    assert fake.started == 0
    assert db.retrain_requests.find_one({"_id": "alert-1"})["status"] == "pending"
    assert load_state(db, NOW).running is True  # the monitor also sees a run in progress


def test_dead_job_does_not_block_retraining_forever(
    mongo_uri: str, registry: FileRegistry, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    client, db = fresh_db(mongo_uri, "ml_orch_crash")
    ensure_schema(db)
    fake = FakeTraining(0)
    monkeypatch.setattr(retrain, "train_candidate", fake)
    started = NOW - LEASE - timedelta(minutes=5)
    # a job claimed the request and died: the lock was never released
    db.retrain_state.insert_one({"_id": STATE_ID, "running": True, "lockedUntil": started + LEASE})
    db.retrain_requests.insert_one({"_id": "dead", "status": "running", "reason": "drift", "startedAt": started})

    assert load_state(db, NOW).running is False  # the monitor may raise a new request
    _request(db, "alert-2", NOW)
    done = run_pending(client, db, registry, tmp_path, NOW)

    assert done is not None and done["_id"] == "alert-2" and fake.started == 1
    dead = db.retrain_requests.find_one({"_id": "dead"})
    assert dead["status"] == "failed" and dead["error"] == "lease-expired"


def test_replicas_raising_the_same_alert_create_one_request(mongo_uri: str) -> None:
    """Every ml pod runs the daily monitor at the same moment: the drift alert must still produce one request."""
    _, db = fresh_db(mongo_uri, "ml_orch_alert")
    ensure_schema(db)
    barrier = threading.Barrier(6)
    ids: list[str | None] = [None] * 6

    def alert(i: int) -> None:
        barrier.wait()
        ids[i] = create_request(db, NOW, "drift", "3", None, None)

    threads = [threading.Thread(target=alert, args=(i,)) for i in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)

    assert [i for i in ids if i is not None] == [f"retrain-{NOW:%Y-%m-%d}"]
    assert db.retrain_requests.count_documents({"status": "pending"}) == 1
