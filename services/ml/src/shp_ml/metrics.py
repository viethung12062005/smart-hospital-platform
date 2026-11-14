"""Prometheus metrics per model_version (ML-25; the canary AnalysisTemplate reads these metrics, 16-mlops §6)."""

from prometheus_client import Counter, Gauge, Histogram

PREDICT_LATENCY = Histogram(
    "shp_ml_predict_seconds",
    "Time to predict one appointment",
    ["model_version"],
    buckets=(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25),
)
PREDICT_ERRORS = Counter("shp_ml_predict_errors_total", "Prediction errors", ["model_version"])
SCORE_HIST = Histogram(
    "shp_ml_score",
    "Distribution of prediction scores (used to compute score PSI between versions)",
    ["model_version"],
    buckets=tuple(round(i / 10, 1) for i in range(1, 11)),
)
HIGH_RISK = Counter("shp_ml_high_risk_total", "Number of high-risk predictions", ["model_version", "high_risk"])
SHADOW_AGREE = Counter(
    "shp_ml_shadow_agreement_total",
    "highRisk agreement between candidate (shadow) and production",
    ["model_version", "agree"],
)
MODEL_INFO = Gauge("shp_ml_model_info", "Currently loaded model", ["model_version", "role"])
EVENTS = Counter("shp_ml_events_total", "Events received by type and outcome", ["type", "outcome"])
SCORING_SKIPPED = Counter("shp_ml_scoring_skipped_total", "Appointments not scored (no model yet)", ["reason"])
DRIFT_PSI = Gauge("shp_ml_feature_psi", "Feature PSI over the last 7 days vs the training set", ["feature"])
SCORE_PSI = Gauge("shp_ml_score_psi", "Prediction score PSI vs registration time")
LIVE_AUC = Gauge("shp_ml_live_auc", "Live AUC over a rolling 14-day window")
DRIFT_ALERT = Gauge("shp_ml_drift_alert", "1 when there is a drift alert or an AUC drop", ["kind"])
