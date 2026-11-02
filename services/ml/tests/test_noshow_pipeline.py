from datetime import UTC, datetime, timedelta

import pandas as pd
import pytest

from shp_ml import synth
from shp_ml.checks import DataCheckError, check
from shp_ml.features import FEATURES, PatientStats, apply_outcome, features_for, time_split, training_frame
from shp_ml.train import train_baseline


@pytest.fixture(scope="module")
def facts() -> pd.DataFrame:
    return synth.generate(rows=6000, patients=2500, seed=7)


def test_generator_matches_spec_and_is_reproducible(facts: pd.DataFrame) -> None:
    check(facts)
    attended = facts[facts["outcome"] != "cancelled"]
    assert 0.15 <= (attended["outcome"] == "noshow").mean() <= 0.21
    assert set(facts["specialty"]) <= set(synth.SPECIALTIES)
    again = synth.generate(rows=6000, patients=2500, seed=7)
    pd.testing.assert_frame_equal(facts, again)
    assert not facts.equals(synth.generate(rows=6000, patients=2500, seed=8))


def test_shifted_dataset_moves_lead_time_rate_and_channel(facts: pd.DataFrame) -> None:
    shifted = synth.generate(rows=6000, patients=2500, seed=7, shift=synth.SHIFTED)

    def lead(df: pd.DataFrame) -> float:
        return float((df["start_at"] - df["booked_at"]).dt.total_seconds().mean())

    def rate(df: pd.DataFrame) -> float:
        return float((df[df["outcome"] != "cancelled"]["outcome"] == "noshow").mean())

    assert lead(shifted) > 1.25 * lead(facts)
    assert rate(shifted) > rate(facts) + 0.04
    assert (shifted["channel"] == "chatbot").mean() > 0.25


def test_checks_stop_pipeline_on_bad_data(facts: pd.DataFrame) -> None:
    bad = facts.copy()
    bad.loc[:200, "booked_at"] = bad.loc[:200, "start_at"] + timedelta(hours=1)
    bad.loc[:500, "channel"] = None
    with pytest.raises(DataCheckError) as e:
        check(bad)
    assert any("channel thiếu" in p for p in e.value.problems)
    bad = facts.copy()
    bad["outcome"] = "noshow"
    with pytest.raises(DataCheckError):
        check(bad)
    with pytest.raises(DataCheckError):
        check(facts.drop(columns=["patient_id"]))


def test_training_features_equal_serving_features(facts: pd.DataFrame) -> None:
    """Một nguồn đặc trưng: phát lại sự kiện như consumer (ML-06) phải ra đúng hàng của training_frame."""
    frame = training_frame(facts)
    pid = facts["patient_id"].value_counts().index[0]
    mine = facts[facts["patient_id"] == pid].sort_values(["booked_at", "start_at"], kind="stable")
    stats = PatientStats()
    resolved: list[tuple[datetime, str, datetime, datetime | None]] = []
    expected = []
    for r in mine.itertuples(index=False):
        b, s = r.booked_at.to_pydatetime(), r.start_at.to_pydatetime()
        c = None if pd.isna(r.cancelled_at) else r.cancelled_at.to_pydatetime()
        for item in sorted([x for x in resolved if x[0] <= b], key=lambda x: x[0]):
            apply_outcome(stats, item[1], item[2], item[3])
        resolved = [x for x in resolved if x[0] > b]
        if r.outcome != "cancelled":
            expected.append(features_for(b, s, r.specialty, r.channel, r.age_band, stats))
        resolved.append(((c if c else s) + timedelta(hours=1), r.outcome, s, c))
    ids = mine[mine["outcome"] != "cancelled"]["appointment_id"].tolist()
    got = frame.set_index("appointment_id").loc[ids].reset_index()
    exp = pd.DataFrame(expected)
    assert len(exp) >= 3
    for col in FEATURES:
        assert got[col].astype(str).tolist() == exp[col].astype(str).tolist(), col


def test_features_ignore_outcomes_not_yet_known() -> None:
    t0 = datetime(2026, 1, 5, 2, 0, tzinfo=UTC)
    facts = pd.DataFrame(
        {
            "appointment_id": ["a", "b"],
            "patient_id": ["p", "p"],
            "specialty": ["ent", "ent"],
            "channel": ["web", "web"],
            "age_band": ["30-39", "30-39"],
            "booked_at": pd.to_datetime([t0, t0 + timedelta(minutes=30)], utc=True),
            "start_at": pd.to_datetime([t0 + timedelta(hours=2), t0 + timedelta(days=3)], utc=True),
            "outcome": ["noshow", "fulfilled"],
            "cancelled_at": pd.Series([pd.NaT, pd.NaT], dtype="datetime64[ns, UTC]"),
        }
    )
    frame = training_frame(facts)
    # lịch thứ hai đặt trước khi lịch đầu diễn ra: chưa được biết no-show
    assert frame.iloc[1]["prior_noshow"] == 0 and frame.iloc[1]["is_first_visit"] == 1


def test_time_split_has_no_overlap(facts: pd.DataFrame) -> None:
    train, valid = time_split(training_frame(facts), validation_months=6)
    assert train["start_at"].max() < valid["start_at"].min()


def test_baseline_learns_signal(facts: pd.DataFrame) -> None:
    _, metrics = train_baseline(facts)
    assert metrics["validation"]["auc"] > 0.68
