"""Pipeline huấn luyện có kiểm soát (US-063, US-071, US-072; 16-mlops §2-3).

dataset Parquet (hash) → kiểm tra dữ liệu → đặc trưng dùng chung → baseline LR + LightGBM (tìm tham số)
→ đánh giá (tổng, nhóm con, calibration, độ trễ) → cổng chất lượng → đăng ký + alias `candidate` + model card.
Không bao giờ đổi alias `production` (việc của shadow/canary). Cùng hash dataset + seed → cùng model.

  python -m shp_ml.pipeline --data data/noshow.parquet --registry runs/registry [--promote-first]
"""

import argparse
import hashlib
import json
import subprocess  # noqa: S404 - chỉ đọc git SHA
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import lightgbm as lgb
import numpy as np
import pandas as pd

from shp_ml.card import model_card
from shp_ml.checks import check
from shp_ml.drift import KEY_FEATURES
from shp_ml.evaluate import (
    GateResult,
    auc,
    choose_threshold,
    ece,
    latency_p95_ms,
    quality_gate,
    subgroup_auc,
)
from shp_ml.features import CATEGORICAL, FEATURES, time_split, training_frame
from shp_ml.model import NoShowModel
from shp_ml.registry import Registry
from shp_ml.train import baseline_pipeline

PARAM_GRID: list[dict[str, Any]] = [
    {"num_leaves": 15, "min_data_in_leaf": 100, "learning_rate": 0.03, "feature_fraction": 0.8},
    {"num_leaves": 31, "min_data_in_leaf": 200, "learning_rate": 0.03, "feature_fraction": 0.8},
    {"num_leaves": 7, "min_data_in_leaf": 100, "learning_rate": 0.05, "feature_fraction": 1.0},
]
MAX_ROUNDS = 1500
TUNING_MONTHS = 3
REFERENCE_ROWS = 2000


def dataset_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _git_sha() -> str:
    try:
        out = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, timeout=5, check=False)  # noqa: S603, S607
        return out.stdout.strip() or "unknown"
    except OSError:
        return "unknown"


def _params(p: dict[str, Any], seed: int) -> dict[str, Any]:
    return {
        "objective": "binary",
        "metric": "auc",
        "verbose": -1,
        "seed": seed,
        "deterministic": True,
        "force_col_wise": True,
        "num_threads": 1,
        **p,
    }


def train_lightgbm(train: pd.DataFrame, seed: int) -> tuple[lgb.Booster, dict[str, Any], float]:
    """Tìm tham số trên 3 tháng cuối của tập train (early stopping), rồi huấn luyện lại trên cả tập train."""
    fit, tune = time_split(train, TUNING_MONTHS)
    best: tuple[float, dict[str, Any], int, np.ndarray] | None = None
    for p in PARAM_GRID:
        booster = lgb.train(
            _params(p, seed),
            lgb.Dataset(fit[FEATURES], fit["label"]),
            num_boost_round=MAX_ROUNDS,
            valid_sets=[lgb.Dataset(tune[FEATURES], tune["label"])],
            callbacks=[lgb.early_stopping(50, verbose=False)],
        )
        score = np.asarray(booster.predict(tune[FEATURES], num_iteration=booster.best_iteration), dtype=float)
        value = auc(tune["label"].to_numpy(), score)
        if best is None or value > best[0]:
            best = (value, p, booster.best_iteration, score)
    assert best is not None
    _, params, rounds, tune_score = best
    threshold = choose_threshold(tune["label"].to_numpy(), tune_score)
    final = lgb.train(_params(params, seed), lgb.Dataset(train[FEATURES], train["label"]), num_boost_round=rounds)
    return final, {**params, "num_boost_round": rounds}, threshold


@dataclass
class TrainingResult:
    gate: GateResult
    metrics: dict[str, Any]
    version: str | None
    run_id: str


def train_candidate(
    facts: pd.DataFrame,
    registry: Registry,
    dataset_sha256: str,
    seed: int = 42,
    trigger: str = "manual",
) -> TrainingResult:
    check(facts)  # sai → DataCheckError, pipeline dừng trước khi train
    frame = training_frame(facts)
    train, valid = time_split(frame)
    y = valid["label"].to_numpy()

    baseline = baseline_pipeline(seed).fit(train[FEATURES], train["label"])
    baseline_auc = auc(y, baseline.predict_proba(valid[FEATURES])[:, 1])

    booster, params, threshold = train_lightgbm(train, seed)
    candidate = NoShowModel(version="candidate", threshold=threshold, booster=booster)
    score = candidate.predict_frame(valid)
    cand_auc = auc(y, score)
    groups = subgroup_auc(valid, score)
    calib = ece(y, score)
    sample = valid[FEATURES].iloc[[0]]
    p95 = latency_p95_ms(lambda: float(candidate.predict_frame(sample)[0]))

    production_auc: float | None = None
    prod_version = registry.alias_version("production")
    if prod_version is not None:
        prod, _ = registry.load(prod_version)
        production_auc = auc(y, prod.predict_frame(valid))

    gate = quality_gate(cand_auc, baseline_auc, groups, calib, p95, production_auc)
    pred = score >= threshold
    metrics: dict[str, Any] = {
        "auc": cand_auc,
        "baselineAuc": baseline_auc,
        "productionAuc": production_auc,
        "ece": calib,
        "latencyP95Ms": p95,
        "threshold": threshold,
        "precisionAtThreshold": round(float(y[pred].mean()), 4) if pred.any() else 0.0,
        "recallAtThreshold": round(float(pred[y == 1].mean()), 4) if (y == 1).any() else 0.0,
        "subgroupAuc": groups,
        "positiveRate": round(float(y.mean()), 4),
        "train": {"n": len(train), "from": str(train["start_at"].min()), "to": str(train["start_at"].max())},
        "validation": {"n": len(valid), "from": str(valid["start_at"].min()), "to": str(valid["start_at"].max())},
    }
    run = {
        "startedAt": datetime.now(UTC).isoformat(),
        "trigger": trigger,
        "params": {**params, "seed": seed},
        "metrics": {k: v for k, v in metrics.items() if isinstance(v, int | float)},
        "tags": {"datasetSha256": dataset_sha256, "gitSha": _git_sha(), "gatePassed": gate.passed},
        "gate": gate.as_dict(),
        "details": metrics,
    }
    run_id = registry.log_run(run)
    if not gate.passed:
        return TrainingResult(gate, metrics, None, run_id)

    ref = valid.sample(n=min(REFERENCE_ROWS, len(valid)), random_state=seed)
    reference = {
        "features": {f: [str(v) if f in CATEGORICAL else float(v) for v in ref[f]] for f in KEY_FEATURES},
        "scores": [round(float(v), 6) for v in candidate.predict_frame(ref)],
    }
    meta = {
        "reference": reference,  # mẫu phân phối lúc đăng ký cho detector drift (ML-19), không có PII
        "threshold": threshold,
        "datasetSha256": dataset_sha256,
        "seed": seed,
        "params": params,
        "runId": run_id,
        "metrics": metrics,
        "gate": gate.as_dict(),
        "registeredAt": datetime.now(UTC).isoformat(),
        "trigger": trigger,
    }
    version = registry.register(candidate.to_text(), meta, model_card(meta))
    registry.set_alias("candidate", version)
    return TrainingResult(gate, metrics, version, run_id)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--registry", type=Path, help="thư mục FileRegistry (bỏ trống khi dùng --mlflow)")
    ap.add_argument("--mlflow", help="MLflow tracking URI")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument(
        "--promote-first",
        action="store_true",
        help="chỉ khi registry chưa có production: đặt luôn candidate làm production (khởi tạo lần đầu)",
    )
    a = ap.parse_args()
    from shp_ml.registry import FileRegistry, MlflowRegistry

    reg: Registry = MlflowRegistry(a.mlflow) if a.mlflow else FileRegistry(a.registry or Path("runs/registry"))
    result = train_candidate(pd.read_parquet(a.data), reg, dataset_hash(a.data), a.seed)
    if result.version and a.promote_first and reg.alias_version("production") is None:
        reg.set_alias("production", result.version)
    print(
        json.dumps(
            {
                "gatePassed": result.gate.passed,
                "version": result.version,
                "runId": result.run_id,
                "failed": [c.name for c in result.gate.failed()],
                "auc": result.metrics["auc"],
                "baselineAuc": result.metrics["baselineAuc"],
            },
            ensure_ascii=False,
        )
    )
    if not result.gate.passed:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
