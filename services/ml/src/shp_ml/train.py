"""Train the Logistic Regression baseline (ML-03) and evaluate with time-based validation.

  python -m shp_ml.train --data data/noshow.parquet --out runs/baseline

Writes metrics.json (AUC, precision/recall at the threshold, dataset hash, seed). Writing to MLflow is the next step
(ML-03, ML-04).
"""

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import precision_score, recall_score, roc_auc_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from shp_ml.checks import check
from shp_ml.features import CATEGORICAL, FEATURES, NUMERIC, time_split, training_frame

THRESHOLD = 0.35  # score ≥ threshold → "high risk" (extra reminder, US-046); re-tuned once LightGBM lands


def baseline_pipeline(seed: int) -> Pipeline:
    pre = ColumnTransformer(
        [
            ("num", StandardScaler(), NUMERIC),
            ("cat", OneHotEncoder(handle_unknown="ignore", min_frequency=20), CATEGORICAL),
        ]
    )
    return Pipeline([("pre", pre), ("lr", LogisticRegression(max_iter=1000, random_state=seed))])


def evaluate(y: np.ndarray, score: np.ndarray, threshold: float = THRESHOLD) -> dict[str, float]:
    pred = (score >= threshold).astype(int)
    return {
        "auc": round(float(roc_auc_score(y, score)), 4),
        "precision": round(float(precision_score(y, pred, zero_division=0)), 4),
        "recall": round(float(recall_score(y, pred, zero_division=0)), 4),
        "positiveRate": round(float(y.mean()), 4),
        "n": int(len(y)),
    }


def train_baseline(facts: pd.DataFrame, seed: int = 42) -> tuple[Pipeline, dict[str, Any]]:
    check(facts)
    frame = training_frame(facts)
    train, valid = time_split(frame)
    model = baseline_pipeline(seed)
    model.fit(train[FEATURES], train["label"])
    score = model.predict_proba(valid[FEATURES])[:, 1]
    metrics = {
        "model": "logistic-regression",
        "threshold": THRESHOLD,
        "train": {"n": len(train), "from": str(train["start_at"].min()), "to": str(train["start_at"].max())},
        "validation": evaluate(valid["label"].to_numpy(), score),
    }
    return model, metrics


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args()
    facts = pd.read_parquet(a.data)
    _, metrics = train_baseline(facts, a.seed)
    metrics["datasetSha256"] = hashlib.sha256(a.data.read_bytes()).hexdigest()
    metrics["seed"] = a.seed
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics["validation"]))


if __name__ == "__main__":
    main()
