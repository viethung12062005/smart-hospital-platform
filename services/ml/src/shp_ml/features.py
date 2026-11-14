"""No-show features shared by training and serving (ML-15, 04 §2.3).

Single source: `features_for` computes the features of ONE appointment from the patient statistics at booking time
(serving, ML-07); `training_frame` replays history over time and calls that same function for each appointment
(training). Only uses information available before `bookedAt`: the outcome of an earlier appointment counts only
once known (startAt + 1 hour ≤ bookedAt).
"""

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import pandas as pd

VN = ZoneInfo("Asia/Ho_Chi_Minh")
OUTCOME_KNOWN_AFTER = timedelta(hours=1)  # no-show is final after end + 30 minutes (SCH-24)
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
    """Same shape as the `ml.patient_stats` document (bookedTotal = appointments with a fulfilled/noshow outcome)."""

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
    """Update the statistics when an outcome becomes known (consumer E2/E5, ML-06)."""
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

    Return features + `label` (1 = noshow) + `start_at`, `appointment_id`; cancelled appointments are excluded from the
    training set but still count toward prior_late_cancel of later appointments.
    """
    df = facts.sort_values(["booked_at", "start_at"], kind="stable").reset_index(drop=True)
    stats: dict[str, PatientStats] = defaultdict(PatientStats)
    # Per-patient queue of outcomes not yet "known": (known time, outcome, start_at, cancelled_at)
    pending: dict[str, list[tuple[datetime, str, datetime, datetime | None]]] = defaultdict(list)
    rows: list[dict[str, Any]] = []
    for r in df.to_dict("records"):
        pid = str(r["patient_id"])
        booked_at: datetime = r["booked_at"]  # pd.Timestamp is a datetime
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
    """Time-based split (ML-02): the last `validation_months` months are validation, no shuffling."""
    cutoff = frame["start_at"].max() - pd.DateOffset(months=validation_months)
    return frame[frame["start_at"] <= cutoff].copy(), frame[frame["start_at"] > cutoff].copy()
