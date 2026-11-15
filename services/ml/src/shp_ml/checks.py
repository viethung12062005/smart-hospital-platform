"""Data checks before training (ML-14, 16-mlops §2). Any failed condition stops the pipeline; no training.

The column schema, dtypes and the categorical value sets are declared once with pandera (`SCHEMA`); the rules pandera
cannot express (temporal leakage, the no-show rate window, the missing-rate ceiling) are checked below. Error messages
mention only column names and rates, never per-row values.
"""

import pandas as pd
import pandera.pandas as pa

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


# The pandera schema mirrors REQUIRED plus the value sets; pandera reports every failing row, so `check` turns its
# errors into the same short, PII-free messages the custom checks use.
SCHEMA = pa.DataFrameSchema(
    {
        # Nulls are governed by MAX_MISSING below, so the schema only checks types and value sets here. The datetime
        # columns stay out of pandera: pandas may hold them at microsecond or nanosecond resolution, and the
        # timezone-aware check below is stricter and resolution-independent.
        "appointment_id": pa.Column(str, nullable=True),
        "patient_id": pa.Column(str, nullable=True),
        "specialty": pa.Column(str, nullable=True, checks=pa.Check.str_length(min_value=1)),
        "channel": pa.Column(str, nullable=True, checks=pa.Check.isin(sorted(CHANNELS))),
        "age_band": pa.Column(str, nullable=True, checks=pa.Check.str_matches(r"\d{1,3}-\d{1,3}|unknown")),
        "outcome": pa.Column(str, nullable=True, checks=pa.Check.isin(sorted(OUTCOMES))),
    },
    strict=False,
    coerce=False,
)


class DataCheckError(ValueError):
    def __init__(self, problems: list[str]) -> None:
        self.problems = problems
        super().__init__("; ".join(problems))


def _pandera_problems(df: pd.DataFrame) -> list[str]:
    """Column/dtype/value-set failures, summarised per column (no row values, so no PII)."""
    try:
        SCHEMA.validate(df, lazy=True)
    except pa.errors.SchemaErrors as exc:
        failures = exc.failure_cases
        counts = failures.groupby("column").size().to_dict()
        return [f"{col}: {n} row(s) fail the schema" for col, n in sorted(counts.items())]
    return []


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
    problems.extend(_pandera_problems(df))
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
