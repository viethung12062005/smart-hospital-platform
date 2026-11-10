"""Daily monitoring job (ML-10, ML-20; 16-mlops §4-5).

1. Drift: distribution of key features and prediction scores over the last 7 days (from `predictions.features`) vs the
   reference sample saved with the model at registration.
2. Live performance: join production predictions with known real outcomes over 14 days, AUC vs at registration.
3. Export Prometheus metrics (alerts and dashboard in MLO-05) and, if `decide_retrain` allows, create a retraining
   request in `retrain_requests` for the ML-23 job to process. This job does not train and does not change the model.

Runs inside the service (thread, 02:00 Vietnam time) or as `python -m shp_ml.monitor` (CronJob).
"""

import logging
import threading
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

import pandas as pd
from pymongo.database import Database

from shp_ml.consumer import labeled_since
from shp_ml.drift import (
    KEY_FEATURES,
    DriftReport,
    PerformanceReport,
    RetrainDecision,
    RetrainState,
    decide_retrain,
    feature_drift,
    live_performance,
)
from shp_ml.metrics import DRIFT_ALERT, DRIFT_PSI, LIVE_AUC, SCORE_PSI
from shp_ml.serving import ModelHolder

LOG = logging.getLogger("shp.ml.monitor")
DRIFT_WINDOW = timedelta(days=7)
PERF_WINDOW = timedelta(days=14)
MIN_RECENT = 200
STATE_ID = "no-show"
Doc = dict[str, Any]


@dataclass
class MonitorResult:
    drift: DriftReport | None
    performance: PerformanceReport | None
    decision: RetrainDecision
    request_id: str | None


def load_state(db: Database[Doc]) -> RetrainState:
    doc = db.retrain_state.find_one({"_id": STATE_ID}) or {}
    last = doc.get("lastTrainedAt")
    return RetrainState(
        last_trained_at=last.replace(tzinfo=UTC) if last and last.tzinfo is None else last,
        labeled_since_last_train=labeled_since(db, last),
        running=bool(doc.get("running")) or db.retrain_requests.count_documents({"status": "pending"}) > 0,
        auto_runs=[t if t.tzinfo else t.replace(tzinfo=UTC) for t in doc.get("autoRuns", [])],
    )


def _recent_predictions(db: Database[Doc], version: str, now: datetime) -> pd.DataFrame:
    rows = list(
        db.predictions.find(
            {"modelVersion": version, "shadow": {"$ne": True}, "predictedAt": {"$gte": now - DRIFT_WINDOW}},
            {"features": 1, "score": 1, "_id": 0},
        )
    )
    return pd.DataFrame([{**{f: r["features"].get(f) for f in KEY_FEATURES}, "score": r["score"]} for r in rows])


def _live_labels(db: Database[Doc], version: str, now: datetime) -> pd.DataFrame:
    facts = {
        f["_id"]: f["outcome"]
        for f in db.appointment_facts.find(
            {"outcome": {"$in": ["fulfilled", "noshow"]}, "outcomeAt": {"$gte": now - PERF_WINDOW}}, {"outcome": 1}
        )
    }
    if not facts:
        return pd.DataFrame(columns=["label", "score"])
    preds = db.predictions.find(
        {"appointmentId": {"$in": list(facts)}, "modelVersion": version, "shadow": {"$ne": True}},
        {"appointmentId": 1, "score": 1},
    )
    return pd.DataFrame([{"label": int(facts[p["appointmentId"]] == "noshow"), "score": p["score"]} for p in preds])


def run_daily(db: Database[Doc], holder: ModelHolder, now: datetime | None = None) -> MonitorResult:
    now = now or datetime.now(UTC)
    prod = holder.production()
    if prod is None:
        return MonitorResult(None, None, RetrainDecision(False, "no-production-model"), None)
    model, meta = prod
    drift: DriftReport | None = None
    ref = meta.get("reference")
    recent = _recent_predictions(db, model.version, now)
    if ref and len(recent) >= MIN_RECENT:
        reference = pd.DataFrame(ref["features"])
        for f in KEY_FEATURES:
            if reference[f].dtype == object:
                recent[f] = recent[f].astype(str)
        drift = feature_drift(reference, recent, pd.Series(ref["scores"]), recent["score"])
        for f, v in drift.feature_psi.items():
            DRIFT_PSI.labels(feature=f).set(v)
        if drift.score_psi is not None:
            SCORE_PSI.set(drift.score_psi)
        DRIFT_ALERT.labels(kind="drift").set(int(drift.alert))

    live = _live_labels(db, model.version, now)
    perf = live_performance(live["label"], live["score"], float(meta.get("metrics", {}).get("auc", 0.0)))
    if perf.auc is not None:
        LIVE_AUC.set(perf.auc)
    DRIFT_ALERT.labels(kind="auc-drop").set(int(perf.alert))

    decision = decide_retrain(now, drift, perf, load_state(db))
    request_id = None
    if decision.run:
        request_id = str(uuid.uuid4())
        db.retrain_requests.insert_one(
            {
                "_id": request_id,
                "status": "pending",
                "reason": decision.reason,
                "createdAt": now,
                "modelVersion": model.version,
                "drift": drift.feature_psi if drift else None,
                "liveAuc": perf.auc,
            }
        )
    elif decision.needs_human:
        DRIFT_ALERT.labels(kind="needs-human").set(1)
    LOG.info("monitor: drift=%s auc=%s decision=%s", drift.alert if drift else None, perf.auc, decision.reason)
    return MonitorResult(drift, perf, decision, request_id)


def seconds_until(hour_utc: int, now: datetime) -> float:
    nxt = now.replace(hour=hour_utc, minute=0, second=0, microsecond=0)
    if nxt <= now:
        nxt += timedelta(days=1)
    return (nxt - now).total_seconds()


def start_daily(db: Database[Doc], holder: ModelHolder, stop: threading.Event, hour_utc: int = 19) -> threading.Thread:
    """19:00 UTC = 02:00 Vietnam time. A failed run is logged; the next run proceeds normally."""

    def loop() -> None:
        while not stop.wait(seconds_until(hour_utc, datetime.now(UTC))):
            try:
                run_daily(db, holder)
            except Exception as exc:  # noqa: BLE001
                LOG.warning("monitor failed: %s", type(exc).__name__)

    t = threading.Thread(target=loop, name="ml-monitor", daemon=True)
    t.start()
    return t


def main() -> None:
    from pymongo import MongoClient

    from shp_ml.main import make_registry
    from shp_ml.settings import Settings

    s = Settings()
    db: Database[Doc] = MongoClient(s.mongodb_uri, tz_aware=True)[s.mongodb_db]
    holder = ModelHolder(make_registry(s), shadow_enabled=False)
    holder.refresh()
    r = run_daily(db, holder)
    print(
        {
            "drift": r.drift.alert if r.drift else None,
            "auc": r.performance.auc if r.performance else None,
            "decision": r.decision.reason,
            "request": r.request_id,
        }
    )


if __name__ == "__main__":
    main()
