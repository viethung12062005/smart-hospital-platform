"""US-064, US-046: consumer + scoring + API (ML-06..09, ML-11, ML-12, ML-24, ML-25) on a real MongoDB."""

import time
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from shp_ml.consumer import BOOKED, CANCELLED, MERGED, RESCHEDULED, STATUS, NoShowConsumer
from shp_ml.main import State, build_app
from shp_ml.registry import FileRegistry
from shp_ml.serving import ModelHolder
from shp_ml.settings import Settings
from shp_ml.store import ensure_schema
from shp_platform import events
from shp_platform.testing import fresh_db

from .conftest import AUTH, bearer

ROOT = Path(__file__).resolve().parents[3]
NOW = datetime.now(UTC).replace(microsecond=0)


def iso(d: datetime) -> str:
    return d.isoformat().replace("+00:00", "Z")


def evt(type_: str, data: dict[str, Any], subject_id: str | None = None) -> dict[str, Any]:
    ctx = type_.split(".")[1]
    return events.create(
        ctx, type_, f"{'Patient' if ctx == 'clinical' else 'Appointment'}/{subject_id or uuid.uuid4()}", data
    )


def booked(
    patient: str,
    appt: str | None = None,
    *,
    lead_days: float = 3,
    version: int = 1,
    channel: str = "web",
    age: str = "70-79",
) -> dict[str, Any]:
    appt = appt or str(uuid.uuid4())
    start = NOW + timedelta(days=lead_days)
    return evt(
        BOOKED,
        {
            "appointmentId": appt,
            "version": version,
            "patientId": patient,
            "practitionerId": str(uuid.uuid4()),
            "slotId": str(uuid.uuid4()),
            "specialty": "cardiology",
            "start": iso(start),
            "end": iso(start + timedelta(minutes=15)),
            "channel": channel,
            "bookedAt": iso(NOW),
            "ageBand": age,
        },
        appt,
    )


def status(patient: str, appt: str, new: str, version: int = 4) -> dict[str, Any]:
    return evt(
        STATUS,
        {
            "appointmentId": appt,
            "version": version,
            "patientId": patient,
            "previousStatus": "booked",
            "newStatus": new,
            "changedAt": iso(NOW),
        },
        appt,
    )


@pytest.fixture()
def env(mongo_uri: str, registry: FileRegistry) -> tuple[Any, Any, NoShowConsumer, ModelHolder]:
    client, db = fresh_db(mongo_uri, f"ml_{uuid.uuid4().hex[:8]}")
    ensure_schema(db)
    ensure_schema(db)  # idempotent (ML-11)
    holder = ModelHolder(registry, shadow_enabled=False)
    holder.refresh()
    return client, db, NoShowConsumer(client, db, holder), holder


def test_no_model_still_records_fact_but_never_scores(mongo_uri: str) -> None:
    client, db = fresh_db(mongo_uri, "ml_nomodel")
    ensure_schema(db)
    c = NoShowConsumer(client, db, ModelHolder(None))
    e = booked(str(uuid.uuid4()))
    c.handle(e)
    assert db.appointment_facts.count_documents({}) == 1
    assert db.predictions.count_documents({}) == 0 and db.outbox.count_documents({}) == 0


def test_booked_is_scored_once_with_model_version_and_features(
    env: tuple[Any, Any, NoShowConsumer, ModelHolder],
) -> None:
    _, db, c, _ = env
    e = booked(str(uuid.uuid4()))
    c.handle(e)
    c.handle(e)  # redelivery: the inbox skips it
    p = db.predictions.find_one({})
    assert db.predictions.count_documents({}) == 1
    assert p["modelVersion"] == "1" and 0 <= p["score"] <= 1 and p["features"]["is_first_visit"] == 1  # AC-15.2
    out = list(db.outbox.find({}))
    assert len(out) == 1 and out[0]["eventType"] == "shp.ml.no-show-scored.v1"
    events.validate(out[0]["payload"])  # ML-12: E10 contract
    assert out[0]["payload"]["data"]["highRisk"] == (p["score"] >= p["threshold"])


def test_outcomes_feed_next_prediction_and_old_versions_are_ignored(
    env: tuple[Any, Any, NoShowConsumer, ModelHolder],
) -> None:
    _, db, c, _ = env
    pid = str(uuid.uuid4())
    first = booked(pid)
    c.handle(first)
    appt = first["data"]["appointmentId"]
    c.handle(status(pid, appt, "noshow", version=4))
    c.handle(status(pid, appt, "fulfilled", version=3))  # older: skipped
    stats = db.patient_stats.find_one({"_id": pid})
    assert (stats["bookedTotal"], stats["noshowTotal"]) == (1, 1)
    assert db.appointment_facts.find_one({"_id": appt})["outcome"] == "noshow"
    second = booked(pid)
    c.handle(second)
    f = db.predictions.find_one({"appointmentId": second["data"]["appointmentId"]})["features"]
    assert (f["prior_noshow"], f["prior_booked"], f["is_first_visit"]) == (1, 1, 0)


def test_late_cancel_and_reschedule(env: tuple[Any, Any, NoShowConsumer, ModelHolder]) -> None:
    _, db, c, _ = env
    pid = str(uuid.uuid4())
    a = booked(pid, channel="chatbot")
    c.handle(a)
    appt = a["data"]["appointmentId"]
    new_id = str(uuid.uuid4())
    c.handle(
        evt(
            RESCHEDULED,
            {
                "oldAppointmentId": appt,
                "newAppointmentId": new_id,
                "version": 1,
                "patientId": pid,
                "practitionerId": str(uuid.uuid4()),
                "oldStart": a["data"]["start"],
                "newStart": iso(NOW + timedelta(days=5)),
                "newEnd": iso(NOW + timedelta(days=5, minutes=15)),
                "specialty": "cardiology",
            },
            new_id,
        )
    )
    assert db.appointment_facts.find_one({"_id": appt})["outcome"] == "cancelled"
    new_fact = db.appointment_facts.find_one({"_id": new_id})
    assert new_fact["channel"] == "chatbot" and new_fact["ageBand"] == "70-79"
    assert db.predictions.count_documents({"appointmentId": new_id}) == 1
    c.handle(
        evt(
            CANCELLED,
            {
                "appointmentId": new_id,
                "version": 2,
                "patientId": pid,
                "practitionerId": str(uuid.uuid4()),
                "start": iso(NOW + timedelta(days=5)),
                "cancelReason": "patient-request",
                "cancelledBy": "patient",
                "cancelledAt": iso(NOW),
                "hoursBeforeStart": 3.0,
            },
            new_id,
        )
    )
    assert db.patient_stats.find_one({"_id": pid})["lateCancelTotal"] == 1
    assert db.appointment_facts.find_one({"_id": new_id})["outcome"] == "cancelled"


def test_patient_merge_moves_stats(env: tuple[Any, Any, NoShowConsumer, ModelHolder]) -> None:
    _, db, c, _ = env
    src, dst = str(uuid.uuid4()), str(uuid.uuid4())
    for pid in (src, dst):
        b = booked(pid)
        c.handle(b)
        c.handle(status(pid, b["data"]["appointmentId"], "noshow"))
    c.handle(evt(MERGED, {"sourcePatientId": src, "targetPatientId": dst, "mergedAt": iso(NOW)}, dst))
    assert db.patient_stats.find_one({"_id": src}) is None
    assert db.patient_stats.find_one({"_id": dst})["noshowTotal"] == 2
    assert db.appointment_facts.count_documents({"patientId": src}) == 0


def test_shadow_scores_candidate_without_affecting_production(
    mongo_uri: str,
    registry: FileRegistry,
) -> None:
    client, db = fresh_db(mongo_uri, "ml_shadow")
    ensure_schema(db)
    registry.set_alias("candidate", "2")
    holder = ModelHolder(registry, shadow_enabled=True)
    holder.refresh()
    c = NoShowConsumer(client, db, holder)
    e = booked(str(uuid.uuid4()))
    c.handle(e)
    assert db.predictions.count_documents({"shadow": False, "modelVersion": "1"}) == 1
    assert db.predictions.count_documents({"shadow": True, "modelVersion": "2"}) == 1
    out = db.outbox.find_one({})
    assert out["payload"]["data"]["modelVersion"] == "1"  # E10 always comes from production


def test_alias_change_is_picked_up_without_redeploy(registry: FileRegistry, tmp_path: Path) -> None:
    holder = ModelHolder(registry)
    holder.refresh()
    assert holder.production()[0].version == "1"  # type: ignore[index]
    registry.set_alias("production", "2")
    try:
        assert holder.refresh() is True
        assert holder.production()[0].version == "2"  # type: ignore[index]
    finally:
        registry.set_alias("production", "1")
    broken = ModelHolder(FileRegistry(tmp_path / "missing"))
    assert broken.refresh() is False and broken.production() is None


def _client(mongo_uri: str, registry: FileRegistry) -> tuple[TestClient, Any]:
    client, db = fresh_db(mongo_uri, "ml_api")
    ensure_schema(db)
    holder = ModelHolder(registry, shadow_enabled=False)
    holder.refresh()
    settings = Settings(mongodb_db="ml_api")
    app = build_app(lambda: State(settings, client, holder, registry), AUTH, ["svc-scheduling"], start_background=False)
    return TestClient(app), db


def test_api_m1_m2_m3_permissions_audit_and_latency(mongo_uri: str, registry: FileRegistry) -> None:
    tc, db = _client(mongo_uri, registry)
    with tc:
        e = booked(str(uuid.uuid4()))
        c = NoShowConsumer(db.client, db, ModelHolder(registry))
        c.holder.refresh()
        c.handle(e)
        appt = e["data"]["appointmentId"]
        nurse = bearer(roles=["nurse"], amr=["mfa"])
        r = tc.get(f"/api/v1/ml/predictions?appointment={appt}", headers=nurse)
        assert r.status_code == 200 and r.json()["modelVersion"] == "1" and "lead_time_days" in r.json()["features"]
        audits = [o["payload"] for o in db.outbox.find({"eventType": "shp.audit.recorded.v1"})]
        assert audits and audits[-1]["data"]["resource"] == f"Appointment/{appt}"
        events.validate(audits[-1])
        assert (
            tc.get(f"/api/v1/ml/predictions?appointment={appt}", headers=bearer(roles=["patient"])).status_code == 403
        )
        assert tc.get(f"/api/v1/ml/predictions?appointment={uuid.uuid4()}", headers=nurse).status_code == 404
        assert tc.get("/api/v1/ml/model", headers=nurse).status_code == 403
        m2 = tc.get("/api/v1/ml/model", headers=bearer(roles=["admin"], amr=["mfa"])).json()
        assert m2["modelVersion"] == "1" and m2["metrics"]["auc"] == 0.78

        body = db.predictions.find_one({"appointmentId": appt})["features"]
        svc = bearer(sub="service-account-svc-scheduling", azp="svc-scheduling", roles=["internal.ml"])
        assert tc.post("/internal/v1/ml/predict", json=body, headers=svc).status_code == 200
        wrong = bearer(azp="svc-ai", roles=["internal.ml"])
        assert tc.post("/internal/v1/ml/predict", json=body, headers=wrong).status_code == 403
        assert tc.post("/internal/v1/ml/predict", json={**body, "channel": "fax"}, headers=svc).status_code == 400
        times = []
        for _ in range(50):
            t0 = time.perf_counter()
            assert tc.post("/internal/v1/ml/predict", json=body, headers=svc).status_code == 200
            times.append(time.perf_counter() - t0)
        assert sorted(times)[int(0.95 * len(times)) - 1] < 0.1  # p95 ≤ 100 ms (M3)
        metrics = tc.get("/metrics").text
        assert 'shp_ml_predict_seconds_count{model_version="1"}' in metrics
