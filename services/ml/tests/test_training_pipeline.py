from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from shp_ml import evaluate, synth
from shp_ml.evaluate import choose_threshold, ece, quality_gate
from shp_ml.model import NoShowModel, to_frame
from shp_ml.pipeline import TrainingResult, train_candidate
from shp_ml.registry import FileRegistry


@pytest.fixture(scope="module")
def facts() -> pd.DataFrame:
    return synth.generate(rows=20000, patients=12000, seed=11)


@pytest.fixture(scope="module")
def trained(facts: pd.DataFrame, tmp_path_factory: pytest.TempPathFactory) -> tuple[FileRegistry, TrainingResult]:
    reg = FileRegistry(tmp_path_factory.mktemp("reg"))
    return reg, train_candidate(facts, reg, "a" * 64, seed=42)


def test_gate_passes_registers_candidate_only(trained: tuple[FileRegistry, TrainingResult]) -> None:
    reg, result = trained
    assert result.gate.passed, [c.name for c in result.gate.failed()]
    assert result.version == "1" and reg.alias_version("candidate") == "1"
    assert reg.alias_version("production") is None  # job huấn luyện không bao giờ đổi production
    assert result.metrics["auc"] > result.metrics["baselineAuc"]
    model, meta = reg.load("1")
    assert meta["datasetSha256"] == "a" * 64 and meta["runId"] == result.run_id
    card = (reg.root / "models" / "1" / "card.md").read_text(encoding="utf-8")
    assert "dữ liệu giả" in card and "Cổng chất lượng" in card


def test_same_dataset_and_seed_give_same_model(
    facts: pd.DataFrame, tmp_path: Path, trained: tuple[FileRegistry, TrainingResult]
) -> None:
    reg, _ = trained
    again = FileRegistry(tmp_path)
    train_candidate(facts, again, "a" * 64, seed=42)
    assert again.load("1")[0].to_text() == reg.load("1")[0].to_text()


def test_failed_gate_registers_nothing_and_logs_reason(
    facts: pd.DataFrame, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(evaluate, "MIN_AUC", 0.99)
    reg = FileRegistry(tmp_path)
    result = train_candidate(facts, reg, "b" * 64)
    assert not result.gate.passed and result.version is None
    assert [c.name for c in result.gate.failed()] == ["auc"]
    assert reg.alias_version("candidate") is None and not (tmp_path / "models").exists()
    assert (tmp_path / "runs" / f"{result.run_id}.json").exists()


def test_candidate_worse_than_production_is_rejected() -> None:
    gate = quality_gate(0.78, 0.76, {}, 0.02, 5, production_auc=0.80)
    assert not gate.passed and gate.failed()[0].name == "no-regression-vs-production"


@pytest.mark.parametrize(
    ("auc", "baseline", "groups", "calib", "p95", "failed"),
    [
        (0.74, 0.70, {}, 0.02, 5, "auc"),
        (0.78, 0.79, {}, 0.02, 5, "auc-vs-baseline"),
        (0.78, 0.70, {"channel": {"chatbot": 0.70}}, 0.02, 5, "subgroup:channel=chatbot"),
        (0.78, 0.70, {}, 0.08, 5, "ece"),
        (0.78, 0.70, {}, 0.02, 150, "latency-p95-ms"),
    ],
)
def test_gate_rules(
    auc: float, baseline: float, groups: dict[str, dict[str, float]], calib: float, p95: float, failed: str
) -> None:
    gate = quality_gate(auc, baseline, groups, calib, p95, None)
    assert [c.name for c in gate.failed()] == [failed]


def test_model_text_is_integrity_checked_and_serves_unknown_categories(
    trained: tuple[FileRegistry, TrainingResult],
) -> None:
    reg, _ = trained
    model, meta = reg.load("1")
    with pytest.raises(ValueError):
        NoShowModel.from_text(model.to_text() + " ", "1", 0.3, meta["modelSha256"])
    row = {
        "lead_time_days": 3,
        "prior_booked": 2,
        "prior_noshow": 1,
        "prior_late_cancel": 0,
        "noshow_rate_smoothed": 2 / 7,
        "days_since_last_noshow": 40,
        "is_first_visit": 0,
        "start_hour": "7",
        "weekday": "2",
        "specialty": "chuyên-khoa-mới",
        "channel": "web",
        "age_band": "70-79",
    }
    assert 0 < model.score(row) < 1
    assert len(to_frame([row, row])) == 2


def test_registry_rejects_bad_alias_and_unknown_version(tmp_path: Path) -> None:
    reg = FileRegistry(tmp_path)
    with pytest.raises(ValueError):
        reg.set_alias("latest", "1")
    with pytest.raises(ValueError):
        reg.set_alias("production", "9")


def test_ece_and_threshold() -> None:
    rng = np.random.default_rng(0)
    s = rng.random(5000)
    y = (rng.random(5000) < s).astype(int)  # điểm đã hiệu chuẩn
    assert ece(y, s) < 0.03 and ece(y, np.clip(s + 0.3, 0, 1)) > 0.2
    t = choose_threshold(y, s)
    assert 0.1 <= t <= 0.6
    assert choose_threshold(np.zeros(100, dtype=int), s[:100]) == evaluate.DEFAULT_THRESHOLD
