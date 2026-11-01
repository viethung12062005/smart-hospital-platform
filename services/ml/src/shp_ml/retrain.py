"""Job huấn luyện lại (ML-23; 16-mlops §5). Chạy trong image huấn luyện (extra `train`) như Kubernetes Job (MLO-04).

Lấy một yêu cầu `pending` do job giám sát tạo → giành khóa (tối đa một lần chạy) → xuất snapshot Parquet các lịch hẹn đã
có kết quả trong 24 tháng (hash nội dung) → `train_candidate` (kiểm tra dữ liệu, cổng chất lượng) → đăng ký alias
`candidate` nếu đạt. KHÔNG BAO GIỜ đổi `production`: candidate đi tiếp shadow và canary (§6).
Không đạt cổng: giữ nguyên production, yêu cầu ghi `failed` kèm cổng không đạt. Mọi lần chạy có audit E11.

  python -m shp_ml.retrain --datasets /data/datasets
"""

import argparse
import hashlib
import logging
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pandas as pd
from pymongo import MongoClient, ReturnDocument
from pymongo.database import Database
from pymongo.errors import DuplicateKeyError

from shp_ml.checks import DataCheckError
from shp_ml.drift import training_window
from shp_ml.monitor import STATE_ID
from shp_ml.pipeline import train_candidate
from shp_ml.registry import Registry
from shp_platform import audit, mongo

LOG = logging.getLogger("shp.ml.retrain")
LEASE = timedelta(hours=2)
Doc = dict[str, Any]


def export_facts(db: Database[Doc], now: datetime) -> pd.DataFrame:
    rows = [
        {
            "appointment_id": f["_id"],
            "patient_id": f["patientId"],
            "practitioner_id": f.get("practitionerId", ""),
            "specialty": f["specialty"],
            "channel": f["channel"],
            "age_band": f["ageBand"],
            "booked_at": f["bookedAt"],
            "start_at": f["startAt"],
            "outcome": f["outcome"],
            "cancelled_at": f.get("outcomeAt") if f["outcome"] == "cancelled" else None,
        }
        for f in db.appointment_facts.find({"outcome": {"$ne": "pending"}})
    ]
    df = pd.DataFrame(rows)
    if df.empty:
        return df
    for c in ("booked_at", "start_at", "cancelled_at"):
        df[c] = pd.to_datetime(df[c], utc=True)
    return training_window(df, now).reset_index(drop=True)


def snapshot(df: pd.DataFrame, datasets: Path, now: datetime) -> tuple[Path, str]:
    """Parquet bất biến: datasets/no-show/<yyyy-MM-dd>/<sha256>.parquet (04 §2.2)."""
    tmp = datasets / "no-show" / ".tmp.parquet"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(tmp, index=False)
    sha = hashlib.sha256(tmp.read_bytes()).hexdigest()
    final = datasets / "no-show" / now.strftime("%Y-%m-%d") / f"{sha}.parquet"
    final.parent.mkdir(parents=True, exist_ok=True)
    tmp.replace(final)
    return final, sha


def _claim(db: Database[Doc], now: datetime) -> Doc | None:
    """Khóa toàn cục: tối đa một lần huấn luyện lại tại một thời điểm (khóa có hạn, job chết thì hết hạn)."""
    try:
        state = db.retrain_state.find_one_and_update(
            {"_id": STATE_ID, "$or": [{"running": {"$ne": True}}, {"lockedUntil": {"$lt": now}}]},
            {"$set": {"running": True, "lockedUntil": now + LEASE}},
            upsert=True,
            return_document=ReturnDocument.AFTER,
        )
    except DuplicateKeyError:
        return None  # đang có lần chạy khác giữ khóa
    if state is None:
        return None
    req = db.retrain_requests.find_one_and_update(
        {"status": "pending"},
        {"$set": {"status": "running", "startedAt": now}},
        sort=[("createdAt", 1)],
        return_document=ReturnDocument.AFTER,
    )
    if req is None:
        db.retrain_state.update_one({"_id": STATE_ID}, {"$set": {"running": False}, "$unset": {"lockedUntil": ""}})
    return req


def run_pending(
    client: MongoClient[Doc], db: Database[Doc], registry: Registry, datasets: Path, now: datetime | None = None
) -> Doc | None:
    now = now or datetime.now(UTC)
    req = _claim(db, now)
    if req is None:
        return None
    result: Doc = {"status": "failed"}
    version: str | None = None
    try:
        facts = export_facts(db, now)
        path, sha = snapshot(facts, datasets, now)
        trained = train_candidate(facts, registry, sha, trigger=f"auto:{req['reason']}")
        version = trained.version
        result = {
            "status": "registered" if version else "rejected",
            "datasetSha256": sha,
            "datasetPath": str(path),
            "runId": trained.run_id,
            "candidateVersion": version,
            "failedChecks": [c.name for c in trained.gate.failed()],
            "auc": trained.metrics["auc"],
        }
    except DataCheckError as exc:
        result = {"status": "rejected", "failedChecks": ["data-check"], "problems": exc.problems}
    except Exception as exc:  # noqa: BLE001 - ghi lại, nhả khóa; production không đổi
        LOG.exception("retrain lỗi")
        result = {"status": "failed", "error": type(exc).__name__}
    finally:
        rec = audit.build(
            service="ml",
            action="create",
            outcome="success" if version else "error",
            actor_subject="ml-retrain",
            actor_role="system",
            resource=f"Model/{version}" if version else "Model",
            purpose_of_use="HOPERAT",
            reason=f"retrain {req['reason']}: {result['status']}"[: audit.MAX_REASON_LENGTH],
        )

        def finish(s: Any) -> None:
            db.retrain_requests.update_one({"_id": req["_id"]}, {"$set": {**result, "finishedAt": now}}, session=s)
            db.retrain_state.update_one(
                {"_id": STATE_ID},
                {
                    "$set": {"running": False, "lastTrainedAt": now},
                    "$unset": {"lockedUntil": ""},
                    "$push": {"autoRuns": now},
                },
                session=s,
            )
            mongo.add_to_outbox(db, s, audit.to_event(rec))

        mongo.in_transaction(client, finish)
    return {**req, **result}


def main() -> None:
    from shp_ml.main import make_registry
    from shp_ml.settings import Settings

    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--datasets", type=Path, required=True)
    a = ap.parse_args()
    s = Settings()
    registry = make_registry(s)
    if registry is None:
        raise SystemExit("cần REGISTRY_DIR hoặc MLFLOW_TRACKING_URI")
    client: MongoClient[Doc] = MongoClient(s.mongodb_uri, tz_aware=True)
    out = run_pending(client, client[s.mongodb_db], registry, a.datasets)
    print(out and {k: out.get(k) for k in ("status", "candidateVersion", "failedChecks", "auc")})


if __name__ == "__main__":
    main()
