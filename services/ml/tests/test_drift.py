from datetime import UTC, datetime, timedelta

import numpy as np
import pandas as pd
import pytest

from shp_ml import synth
from shp_ml.drift import (
    DriftReport,
    PerformanceReport,
    RetrainState,
    decide_retrain,
    feature_drift,
    live_performance,
    psi,
    training_window,
)
from shp_ml.features import training_frame

NOW = datetime(2026, 11, 1, tzinfo=UTC)


@pytest.fixture(scope="module")
def frames() -> tuple[pd.DataFrame, pd.DataFrame]:
    base = training_frame(synth.generate(rows=5000, patients=2000, seed=3))
    shifted = training_frame(synth.generate(rows=5000, patients=2000, seed=4, shift=synth.SHIFTED))
    return base, shifted


def test_psi_is_zero_for_same_distribution_and_large_for_shift() -> None:
    rng = np.random.default_rng(1)
    a = pd.Series(rng.normal(0, 1, 5000))
    assert psi(a, pd.Series(rng.normal(0, 1, 5000))) < 0.02
    assert psi(a, pd.Series(rng.normal(1, 1, 5000))) > 0.2
    assert psi(pd.Series(["web"] * 90 + ["staff"] * 10), pd.Series(["web"] * 50 + ["chatbot"] * 50)) > 0.2


def test_no_alert_on_fresh_data_from_same_generator(frames: tuple[pd.DataFrame, pd.DataFrame]) -> None:
    base, _ = frames
    other = training_frame(synth.generate(rows=5000, patients=2000, seed=5))
    assert not feature_drift(base, other).alert


def test_shifted_dataset_triggers_drift_alert(frames: tuple[pd.DataFrame, pd.DataFrame]) -> None:
    base, shifted = frames
    report = feature_drift(base, shifted)
    assert report.alert and {"start_hour", "channel"} <= set(report.drifted)


def test_live_performance_needs_500_labels() -> None:
    rng = np.random.default_rng(2)
    y = pd.Series(rng.integers(0, 2, 400))
    assert live_performance(y, pd.Series(rng.random(400)), 0.8).alert is False
    y = pd.Series(rng.integers(0, 2, 800))
    rep = live_performance(y, pd.Series(rng.random(800)), 0.8)  # random scores: AUC ≈ 0.5
    assert rep.alert and rep.auc is not None and rep.auc < 0.6


DRIFT = DriftReport({}, None, ["lead_time_days", "channel"], True)
QUIET = DriftReport({}, None, [], False)


@pytest.mark.parametrize(
    ("drift", "perf", "state", "run", "reason"),
    [
        (QUIET, None, RetrainState(None, 900), False, "no-trigger"),
        (DRIFT, None, RetrainState(NOW - timedelta(days=10), 900), True, "drift"),
        (QUIET, PerformanceReport(0.7, 900, True), RetrainState(None, 900), True, "auc-drop"),
        (DRIFT, None, RetrainState(NOW - timedelta(days=3), 900), False, "cooldown"),
        (DRIFT, None, RetrainState(None, 120), False, "not-enough-labels"),
        (DRIFT, None, RetrainState(None, 900, running=True), False, "already-running"),
        (
            DRIFT,
            None,
            RetrainState(None, 900, auto_runs=[NOW - timedelta(days=9), NOW - timedelta(days=1)]),
            False,
            "auto-run-limit",
        ),
    ],
)
def test_retrain_decision(
    drift: DriftReport, perf: PerformanceReport | None, state: RetrainState, run: bool, reason: str
) -> None:
    d = decide_retrain(NOW, drift, perf, state)
    assert (d.run, d.reason) == (run, reason)
    assert d.needs_human == (reason == "auto-run-limit")


def test_training_window_drops_old_and_pending() -> None:
    facts = pd.DataFrame(
        {
            "start_at": pd.to_datetime([NOW - timedelta(days=800), NOW - timedelta(days=10), NOW], utc=True),
            "outcome": ["fulfilled", "noshow", "pending"],
        }
    )
    assert training_window(facts, NOW)["outcome"].tolist() == ["noshow"]
