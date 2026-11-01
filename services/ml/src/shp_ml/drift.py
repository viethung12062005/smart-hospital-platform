"""Giám sát drift, hiệu năng thực tế và quyết định huấn luyện lại (ML-19, ML-20, ML-22; 16-mlops §4-5).

Hàm thuần, không gọi mạng: job hằng ngày (ML-10) đọc dữ liệu, gọi các hàm này, xuất metric và nếu `decide_retrain`
cho phép thì kích hoạt job huấn luyện lại (ML-23). Job huấn luyện lại không bao giờ tự đổi alias production.
"""

from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import datetime, timedelta

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

KEY_FEATURES = ["lead_time_days", "prior_noshow", "start_hour", "channel"]
PSI_ALERT = 0.2
MIN_FEATURES_DRIFTED = 2
AUC_DROP_ALERT = 0.05
MIN_LABELED = 500
COOLDOWN = timedelta(days=7)
MAX_AUTO_RUNS = 2
AUTO_RUN_WINDOW = timedelta(days=14)
_EPS = 1e-4


def psi(expected: pd.Series, actual: pd.Series, bins: int = 10) -> float:
    """Population Stability Index. Số: chia theo phân vị của tập tham chiếu; phân loại: theo từng giá trị."""
    if not pd.api.types.is_numeric_dtype(expected.dtype):
        e = expected.astype(str).value_counts(normalize=True)
        a = actual.astype(str).value_counts(normalize=True)
        cats = e.index.union(a.index)
        pe = e.reindex(cats, fill_value=0).to_numpy()
        pa = a.reindex(cats, fill_value=0).to_numpy()
    else:
        ref = expected.to_numpy(dtype=float)
        edges = np.unique(np.quantile(ref, np.linspace(0, 1, bins + 1)))
        edges[0], edges[-1] = -np.inf, np.inf
        pe = np.histogram(ref, edges)[0] / max(len(ref), 1)
        pa = np.histogram(actual.to_numpy(dtype=float), edges)[0] / max(len(actual), 1)
    pe = np.clip(pe, _EPS, None)
    pa = np.clip(pa, _EPS, None)
    return float(np.sum((pa - pe) * np.log(pa / pe)))


@dataclass
class DriftReport:
    feature_psi: dict[str, float]
    score_psi: float | None
    drifted: list[str]
    alert: bool


def feature_drift(
    reference: pd.DataFrame,
    recent: pd.DataFrame,
    reference_scores: pd.Series | None = None,
    recent_scores: pd.Series | None = None,
    features: Sequence[str] = KEY_FEATURES,
) -> DriftReport:
    """Cảnh báo khi PSI > 0,2 ở ≥ 2 đặc trưng chính, hoặc PSI điểm dự đoán > 0,2."""
    values = {f: round(psi(reference[f], recent[f]), 4) for f in features}
    drifted = [f for f, v in values.items() if v > PSI_ALERT]
    score = None
    if reference_scores is not None and recent_scores is not None:
        score = round(psi(reference_scores, recent_scores), 4)
    alert = len(drifted) >= MIN_FEATURES_DRIFTED or (score is not None and score > PSI_ALERT)
    return DriftReport(values, score, drifted, alert)


@dataclass
class PerformanceReport:
    auc: float | None
    labeled: int
    alert: bool


def live_performance(labels: pd.Series, scores: pd.Series, registered_auc: float) -> PerformanceReport:
    """AUC thực tế (cửa sổ 14 ngày do người gọi lọc). Chỉ cảnh báo khi đủ 500 nhãn và giảm > 0,05."""
    n = int(labels.notna().sum())
    if n < MIN_LABELED or labels.nunique() < 2:
        return PerformanceReport(None, n, False)
    auc = float(roc_auc_score(labels, scores))
    return PerformanceReport(round(auc, 4), n, registered_auc - auc > AUC_DROP_ALERT)


@dataclass
class RetrainState:
    last_trained_at: datetime | None = None
    labeled_since_last_train: int = 0
    running: bool = False
    auto_runs: list[datetime] = field(default_factory=list)


@dataclass(frozen=True)
class RetrainDecision:
    run: bool
    reason: str  # ghi vào audit và cảnh báo
    needs_human: bool = False


def decide_retrain(
    now: datetime, drift: DriftReport | None, perf: PerformanceReport | None, state: RetrainState
) -> RetrainDecision:
    triggers = [name for name, hit in (("drift", drift and drift.alert), ("auc-drop", perf and perf.alert)) if hit]
    if not triggers:
        return RetrainDecision(False, "no-trigger")
    if state.running:
        return RetrainDecision(False, "already-running")
    if state.last_trained_at is not None and now - state.last_trained_at < COOLDOWN:
        return RetrainDecision(False, "cooldown")
    if state.labeled_since_last_train < MIN_LABELED:
        return RetrainDecision(False, "not-enough-labels")
    recent = [t for t in state.auto_runs if now - t < AUTO_RUN_WINDOW]
    if len(recent) >= MAX_AUTO_RUNS:
        return RetrainDecision(False, "auto-run-limit", needs_human=True)
    return RetrainDecision(True, "+".join(triggers))


def training_window(facts: pd.DataFrame, now: datetime, months: int = 24) -> pd.DataFrame:
    """Cửa sổ trượt 24 tháng gần nhất, chỉ lịch hẹn đã có kết quả (bỏ pending)."""
    start = pd.Timestamp(now) - pd.DateOffset(months=months)
    return facts[(facts["start_at"] >= start) & (facts["outcome"] != "pending")].copy()
