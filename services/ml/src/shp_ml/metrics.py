"""Metric Prometheus theo model_version (ML-25; AnalysisTemplate của canary đọc các metric này, 16-mlops §6)."""

from prometheus_client import Counter, Gauge, Histogram

PREDICT_LATENCY = Histogram(
    "shp_ml_predict_seconds",
    "Thời gian dự đoán một lịch hẹn",
    ["model_version"],
    buckets=(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25),
)
PREDICT_ERRORS = Counter("shp_ml_predict_errors_total", "Lỗi khi dự đoán", ["model_version"])
SCORE_HIST = Histogram(
    "shp_ml_score",
    "Phân phối điểm dự đoán (tính PSI điểm số giữa phiên bản)",
    ["model_version"],
    buckets=tuple(round(i / 10, 1) for i in range(1, 11)),
)
HIGH_RISK = Counter("shp_ml_high_risk_total", "Số dự đoán nguy cơ cao", ["model_version", "high_risk"])
SHADOW_AGREE = Counter(
    "shp_ml_shadow_agreement_total",
    "Đồng thuận highRisk giữa candidate (shadow) và production",
    ["model_version", "agree"],
)
MODEL_INFO = Gauge("shp_ml_model_info", "Model đang nạp", ["model_version", "role"])
EVENTS = Counter("shp_ml_events_total", "Event đã nhận theo loại và kết quả", ["type", "outcome"])
SCORING_SKIPPED = Counter("shp_ml_scoring_skipped_total", "Lịch hẹn không chấm điểm (chưa có model)", ["reason"])
DRIFT_PSI = Gauge("shp_ml_feature_psi", "PSI đặc trưng 7 ngày gần nhất so với tập huấn luyện", ["feature"])
SCORE_PSI = Gauge("shp_ml_score_psi", "PSI điểm dự đoán so với lúc đăng ký")
LIVE_AUC = Gauge("shp_ml_live_auc", "AUC thực tế cuộn 14 ngày")
DRIFT_ALERT = Gauge("shp_ml_drift_alert", "1 khi có cảnh báo drift hoặc AUC giảm", ["kind"])
