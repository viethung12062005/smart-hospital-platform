"""Data checks before training (ML-14, 16-mlops §2). Any failed condition stops the pipeline; no training.

Error messages mention only column names and rates, never per-row values.
"""

import pandas as pd

REQUIRED = {
    "appointment_id": "object",
    "patient_id": "object",
    "specialty": "object",
    "channel": "object",
    "age_band": "object",
    "booked_at": "datetime",
    "start_at": "datetime",
    "outcome": "object",
}
CHANNELS = {"web", "chatbot", "staff"}
OUTCOMES = {"fulfilled", "noshow", "cancelled"}
MAX_MISSING = 0.02
RATE_RANGE = (0.10, 0.30)


class DataCheckError(ValueError):
    def __init__(self, problems: list[str]) -> None:
        self.problems = problems
        super().__init__("; ".join(problems))


def check(df: pd.DataFrame) -> None:
    problems: list[str] = []
    for col, kind in REQUIRED.items():
        if col not in df.columns:
            problems.append(f"missing column {col}")
            continue
        if kind == "datetime" and not isinstance(df[col].dtype, pd.DatetimeTZDtype):
            problems.append(f"{col} must be a timezone-aware datetime")
        missing = float(df[col].isna().mean())
        if missing > MAX_MISSING:
            problems.append(f"{col} missing {missing:.1%} (> {MAX_MISSING:.0%})")
    if problems:
        raise DataCheckError(problems)
    if df["appointment_id"].duplicated().any():
        problems.append("appointment_id is duplicated")
    if not set(df["channel"].dropna()) <= CHANNELS:
        problems.append("channel has a value outside the allowed set")
    if not set(df["outcome"].dropna()) <= OUTCOMES:
        problems.append("outcome has a value outside the allowed set (pending must not enter the training set)")
    bad_age = ~df["age_band"].astype(str).str.fullmatch(r"\d{1,3}-\d{1,3}|unknown")
    if bad_age.any():
        problems.append(f"age_band has a bad format in {float(bad_age.mean()):.2%} of rows")
    lead = (df["start_at"] - df["booked_at"]).dt.total_seconds()
    if (lead <= 0).any():
        problems.append("booked_at must be before start_at (time leakage)")
    if (lead > 31 * 86400).any():
        problems.append("lead time exceeds the 30-day booking window")
    attended = df[df["outcome"] != "cancelled"]
    rate = float((attended["outcome"] == "noshow").mean()) if len(attended) else 0.0
    if not RATE_RANGE[0] <= rate <= RATE_RANGE[1]:
        problems.append(f"no-show rate {rate:.1%} outside the range [{RATE_RANGE[0]:.0%}, {RATE_RANGE[1]:.0%}]")
    if problems:
        raise DataCheckError(problems)
