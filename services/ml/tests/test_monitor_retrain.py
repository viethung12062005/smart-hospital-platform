"""US-065, US-074: giám sát hằng ngày → yêu cầu huấn luyện lại → job huấn luyện lại (ML-10, ML-20, ML-23)."""

from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pandas as pd
import pytest

from shp_ml import synth
from shp_ml.features import FEATURES, training_frame
from shp_ml.monitor import run_daily
from shp_ml.pipeline import train_candidate
from shp_ml.registry import FileRegistry
from shp_ml.retrain import run_pending
from shp_ml.serving import ModelHolder
from shp_ml.store import ensure_schema
from shp_platform import events
from shp_platform.testing import fresh_db

NOW = datetime.now(UTC).replace(microsecond=0)


@pytest.fixture(scope="module")
def prod_registry(tmp_path_factory: pytest.TempPathFactory) -> FileRegistry:
    reg = FileRegistry(tmp_path_factory.mktemp("prod-reg"))
    result = train_candidate(synth.generate(rows=20000, patients=12000, seed=21), reg, "c" * 64)
    assert result.version is not None
    reg.set_alias("production", result.version)
    assert "reference" in reg.load(result.version)[1]
    return reg


def _seed_recent(db: Any, holder: ModelHolder, facts: pd.DataFrame, n: int) -> None:
    """Giả lập vận hành: n lịch hẹn gần đây đã được chấm điểm và đã có kết quả (đặc trưng lấy từ dữ liệu facts)."""
    model = holder.production()[0]  # type: ignore[index]
    frame = training_frame(facts).tail(n).reset_index(drop=True)
    scores = model.predict_frame(frame)
    preds, outs = [], []
    for i, row in enumerate(frame.to_dict("records")):
        aid = f"{row['appointment_id']}-{i}"
        feats = {
            f: (str(row[f]) if f in ("start_hour", "weekday", "specialty", "channel", "age_band") else float(row[f]))
            for f in FEATURES
        }
        preds.append(
            {
                "appointmentId": aid,
                "patientId": "p",
                "modelName": "shp-no-show",
                "modelVersion": model.version,
                "score": float(scores[i]),
                "threshold": model.threshold,
                "highRisk": False,
                "features": feats,
                "predictedAt": NOW - timedelta(days=1),
                "latencyMs": 1,
                "shadow": False,
            }
        )
        outs.append(
            {
                "_id": aid,
                "patientId": "p",
                "practitionerId": "x",
                "specialty": str(row["specialty"]),
                "channel": str(row["channel"]),
                "ageBand": str(row["age_band"]),
                "bookedAt": NOW - timedelta(days=5),
                "startAt": NOW - timedelta(days=2),
                "outcome": "noshow" if row["label"] else "fulfilled",
                "outcomeAt": NOW - timedelta(days=1),
                "version": 4,
            }
        )
    db.predictions.insert_many(preds)
    db.appointment_facts.insert_many(outs)


def test_stable_traffic_does_not_trigger_retrain(mongo_uri: str, prod_registry: FileRegistry) -> None:
    _, db = fresh_db(mongo_uri, "ml_monitor_stable")
    ensure_schema(db)
    holder = ModelHolder(prod_registry, shadow_enabled=False)
    holder.refresh()
    _seed_recent(db, holder, synth.generate(rows=8000, patients=5000, seed=99), 1500)
    r = run_daily(db, holder, NOW)
    assert r.drift is not None and not r.drift.alert
    assert r.decision.run is False and r.request_id is None


def test_drift_creates_request_and_retrain_registers_candidate_only(
    mongo_uri: str, prod_registry: FileRegistry, tmp_path: Path
) -> None:
    client, db = fresh_db(mongo_uri, "ml_monitor_drift")
    ensure_schema(db)
    holder = ModelHolder(prod_registry, shadow_enabled=False)
    holder.refresh()
    shifted = synth.generate(rows=20000, patients=12000, seed=31, shift=synth.SHIFTED)
    _seed_recent(db, holder, shifted, 1500)

    r = run_daily(db, holder, NOW)
    assert r.drift is not None and r.drift.alert
    assert r.decision.run and r.request_id is not None
    again = run_daily(db, holder, NOW)
    assert again.decision.reason == "already-running" and again.request_id is None  # không tạo yêu cầu trùng

    # lịch sử đã có kết quả (24 tháng) cho job huấn luyện lại
    hist = shifted[shifted["start_at"] < pd.Timestamp(NOW - timedelta(days=3))]
    docs = []
    for r_ in hist.to_dict("records"):
        known = r_["cancelled_at"] if r_["outcome"] == "cancelled" else r_["start_at"]
        docs.append(
            {
                "_id": r_["appointment_id"],
                "patientId": r_["patient_id"],
                "practitionerId": r_["practitioner_id"],
                "specialty": r_["specialty"],
                "channel": r_["channel"],
                "ageBand": r_["age_band"],
                "bookedAt": r_["booked_at"],
                "startAt": r_["start_at"],
                "outcome": r_["outcome"],
                "outcomeAt": known,
                "version": 4,
            }
        )
    db.appointment_facts.insert_many(docs)
    prod_before = prod_registry.alias_version("production")
    done = run_pending(client, db, prod_registry, tmp_path, NOW)
    assert done is not None and done["status"] in ("registered", "rejected"), done
    assert prod_registry.alias_version("production") == prod_before  # không bao giờ tự đổi production
    if done["status"] == "registered":
        assert prod_registry.alias_version("candidate") == done["candidateVersion"]
    assert Path(done["datasetPath"]).exists() and done["datasetSha256"] in done["datasetPath"]
    audit_evt = db.outbox.find_one({"eventType": "shp.audit.recorded.v1"})
    events.validate(audit_evt["payload"])
    assert audit_evt["payload"]["data"]["actor"]["role"] == "system"
    state = db.retrain_state.find_one({"_id": "no-show"})
    assert state["running"] is False and len(state["autoRuns"]) == 1
    assert run_pending(client, db, prod_registry, tmp_path, NOW) is None  # không còn yêu cầu
    after = run_daily(db, holder, NOW + timedelta(days=1))
    assert after.decision.reason in ("cooldown", "no-trigger")  # cooldown 7 ngày
