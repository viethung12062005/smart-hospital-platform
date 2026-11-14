"""Evaluation and quality gates (ML-17, ML-05; 16-mlops §3).

Gate: AUC ≥ 0.75 and above baseline; each subgroup (specialty, age band, channel; ≥ 300 samples) must not be more than
0.07 below the overall AUC; ECE ≤ 0.05; p95 prediction latency ≤ 100 ms; no more than 0.01 AUC worse than the
production model on the same set.
"""

import time
from collections.abc import Callable
from dataclasses import asdict, dataclass, field
from typing import Any

import numpy as np
import pandas as pd
from sklearn.metrics import precision_score, recall_score, roc_auc_score

MIN_AUC = 0.75
MAX_SUBGROUP_GAP = 0.07
MIN_SUBGROUP_SIZE = 300
MAX_ECE = 0.05
MAX_P95_MS = 100.0
MAX_REGRESSION = 0.01
SUBGROUPS = ["specialty", "age_band", "channel"]
DEFAULT_THRESHOLD = 0.60
MIN_PRECISION = 0.5


def auc(y: np.ndarray, s: np.ndarray) -> float:
    return round(float(roc_auc_score(y, s)), 4) if len(np.unique(y)) == 2 else float("nan")


def ece(y: np.ndarray, s: np.ndarray, bins: int = 10) -> float:
    """Expected Calibration Error, split into 10 equal score bins."""
    edges = np.linspace(0, 1, bins + 1)
    idx = np.clip(np.digitize(s, edges[1:-1]), 0, bins - 1)
    total = 0.0
    for b in range(bins):
        m = idx == b
        if m.any():
            total += m.mean() * abs(float(y[m].mean()) - float(s[m].mean()))
    return round(total, 4)


def choose_threshold(y: np.ndarray, s: np.ndarray) -> float:
    """Threshold giving the largest recall with precision ≥ 0.5 (11 §3); otherwise the default 0.60."""
    best: tuple[float, float] | None = None
    for t in np.round(np.arange(0.10, 0.91, 0.01), 2):
        pred = s >= t
        if not pred.any():
            continue
        p = float(precision_score(y, pred, zero_division=0))
        r = float(recall_score(y, pred, zero_division=0))
        if p >= MIN_PRECISION and (best is None or r > best[1]):
            best = (float(t), r)
    return best[0] if best else DEFAULT_THRESHOLD


def subgroup_auc(frame: pd.DataFrame, s: np.ndarray) -> dict[str, dict[str, float]]:
    out: dict[str, dict[str, float]] = {}
    y = frame["label"].to_numpy()
    for col in SUBGROUPS:
        groups: dict[str, float] = {}
        values = frame[col].astype(str).to_numpy()
        for g in sorted(set(values)):
            m = values == g
            if m.sum() >= MIN_SUBGROUP_SIZE and len(np.unique(y[m])) == 2:
                groups[g] = auc(y[m], s[m])
        out[col] = groups
    return out


def latency_p95_ms(predict_one: Callable[[], float], runs: int = 200) -> float:
    times = []
    for _ in range(runs):
        t0 = time.perf_counter()
        predict_one()
        times.append((time.perf_counter() - t0) * 1000)
    return round(float(np.percentile(times, 95)), 2)


@dataclass
class Check:
    name: str
    value: float
    limit: float
    passed: bool

    def __post_init__(self) -> None:  # numpy → Python types for JSON serialization
        self.value, self.limit, self.passed = float(self.value), float(self.limit), bool(self.passed)


@dataclass
class GateResult:
    passed: bool
    checks: list[Check] = field(default_factory=list)

    def failed(self) -> list[Check]:
        return [c for c in self.checks if not c.passed]

    def as_dict(self) -> dict[str, Any]:
        return {"passed": self.passed, "checks": [asdict(c) for c in self.checks]}


def quality_gate(
    candidate_auc: float,
    baseline_auc: float,
    subgroups: dict[str, dict[str, float]],
    calibration_error: float,
    p95_ms: float,
    production_auc: float | None,
) -> GateResult:
    checks = [
        Check("auc", candidate_auc, MIN_AUC, candidate_auc >= MIN_AUC),
        Check("auc-vs-baseline", candidate_auc, baseline_auc, candidate_auc > baseline_auc),
        Check("ece", calibration_error, MAX_ECE, calibration_error <= MAX_ECE),
        Check("latency-p95-ms", p95_ms, MAX_P95_MS, p95_ms <= MAX_P95_MS),
    ]
    for col, groups in subgroups.items():
        for g, value in groups.items():
            gap = round(candidate_auc - value, 4)
            checks.append(
                Check(f"subgroup:{col}={g}", value, round(candidate_auc - MAX_SUBGROUP_GAP, 4), gap <= MAX_SUBGROUP_GAP)
            )
    if production_auc is not None:
        checks.append(
            Check(
                "no-regression-vs-production",
                candidate_auc,
                round(production_auc - MAX_REGRESSION, 4),
                candidate_auc >= production_auc - MAX_REGRESSION,
            )
        )
    return GateResult(bool(all(c.passed for c in checks)), checks)
