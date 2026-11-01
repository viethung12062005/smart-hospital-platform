"""Đặc trưng no-show dùng chung cho huấn luyện và phục vụ (ML-15, 04 §2.3).

Một nguồn duy nhất: `features_for` tính đặc trưng cho MỘT lịch hẹn từ thống kê bệnh nhân tại thời điểm đặt
(phục vụ, ML-07); `training_frame` phát lại lịch sử theo thời gian và gọi đúng hàm đó cho từng lịch hẹn (huấn luyện).
Chỉ dùng thông tin có trước `bookedAt`: kết quả của lịch hẹn cũ chỉ được tính khi đã biết (startAt + 1 giờ ≤ bookedAt).
"""

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import pandas as pd

VN = ZoneInfo("Asia/Ho_Chi_Minh")
OUTCOME_KNOWN_AFTER = timedelta(hours=1)  # no-show chốt sau end + 30 phút (SCH-24)
LATE_CANCEL = timedelta(hours=24)
NO_NOSHOW_DAYS = 9999

NUMERIC = [
    "lead_time_days",
    "prior_booked",
    "prior_noshow",
    "prior_late_cancel",
    "noshow_rate_smoothed",
    "days_since_last_noshow",
    "is_first_visit",
]
CATEGORICAL = ["start_hour", "weekday", "specialty", "channel", "age_band"]
FEATURES = NUMERIC + CATEGORICAL


@dataclass
class PatientStats:
    """Giống document `ml.patient_stats` (bookedTotal = số lịch đã có kết quả fulfilled/noshow)."""

    booked_total: int = 0
    noshow_total: int = 0
    late_cancel_total: int = 0
    last_noshow_at: datetime | None = None


def features_for(
    booked_at: datetime,
    start_at: datetime,
    specialty: str,
    channel: str,
    age_band: str,
    stats: PatientStats,
) -> dict[str, Any]:
    start_vn = start_at.astimezone(VN)
    last = stats.last_noshow_at
    return {
        "lead_time_days": round((start_at - booked_at).total_seconds() / 86400, 4),
        "prior_booked": stats.booked_total,
        "prior_noshow": stats.noshow_total,
        "prior_late_cancel": stats.late_cancel_total,
        "noshow_rate_smoothed": round((stats.noshow_total + 1) / (stats.booked_total + 5), 6),
        "days_since_last_noshow": NO_NOSHOW_DAYS if last is None else max(0, (booked_at - last).days),
        "is_first_visit": int(stats.booked_total == 0),
        "start_hour": str(start_vn.hour),
        "weekday": str(start_vn.isoweekday()),
        "specialty": specialty,
        "channel": channel,
        "age_band": age_band,
    }


def apply_outcome(stats: PatientStats, outcome: str, start_at: datetime, cancelled_at: datetime | None) -> None:
    """Cập nhật thống kê khi biết kết quả (consumer E2/E5, ML-06)."""
    if outcome == "noshow":
        stats.booked_total += 1
        stats.noshow_total += 1
        stats.last_noshow_at = start_at
    elif outcome == "fulfilled":
        stats.booked_total += 1
    elif outcome == "cancelled" and cancelled_at is not None and start_at - cancelled_at < LATE_CANCEL:
        stats.late_cancel_total += 1


def training_frame(facts: pd.DataFrame) -> pd.DataFrame:
    """facts: appointment_id, patient_id, booked_at, start_at, specialty, channel, age_band, outcome,
    cancelled_at (UTC, tz-aware).

    Trả đặc trưng + `label` (1 = noshow) + `start_at`, `appointment_id`; lịch hẹn cancelled bị loại khỏi tập
    huấn luyện nhưng vẫn được tính vào prior_late_cancel của các lịch sau.
    """
    df = facts.sort_values(["booked_at", "start_at"], kind="stable").reset_index(drop=True)
    stats: dict[str, PatientStats] = defaultdict(PatientStats)
    # Hàng đợi kết quả chưa được "biết" theo từng bệnh nhân: (thời điểm biết, outcome, start_at, cancelled_at)
    pending: dict[str, list[tuple[datetime, str, datetime, datetime | None]]] = defaultdict(list)
    rows: list[dict[str, Any]] = []
    for r in df.to_dict("records"):
        pid = str(r["patient_id"])
        booked_at: datetime = r["booked_at"]  # pd.Timestamp là datetime
        start_at: datetime = r["start_at"]
        cancelled_at: datetime | None = None if pd.isna(r["cancelled_at"]) else r["cancelled_at"]
        outcome = str(r["outcome"])
        queue = pending[pid]
        if queue:
            known = [q for q in queue if q[0] <= booked_at]
            if known:
                for _, prev, s_at, c_at in sorted(known, key=lambda q: q[0]):
                    apply_outcome(stats[pid], prev, s_at, c_at)
                pending[pid] = [q for q in queue if q[0] > booked_at]
        if outcome != "cancelled":
            row = features_for(
                booked_at, start_at, str(r["specialty"]), str(r["channel"]), str(r["age_band"]), stats[pid]
            )
            row["label"] = int(outcome == "noshow")
            row["start_at"] = start_at
            row["appointment_id"] = r["appointment_id"]
            rows.append(row)
        known_at = cancelled_at if outcome == "cancelled" and cancelled_at is not None else start_at
        pending[pid].append((known_at + OUTCOME_KNOWN_AFTER, outcome, start_at, cancelled_at))
    out = pd.DataFrame(rows)
    for c in CATEGORICAL:
        out[c] = out[c].astype("category")
    return out


def time_split(frame: pd.DataFrame, validation_months: int = 6) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Chia theo thời gian (ML-02): `validation_months` tháng cuối làm validation, không xáo trộn."""
    cutoff = frame["start_at"].max() - pd.DateOffset(months=validation_months)
    return frame[frame["start_at"] <= cutoff].copy(), frame[frame["start_at"] > cutoff].copy()
