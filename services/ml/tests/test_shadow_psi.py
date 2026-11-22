"""US-073 (ML-25): score PSI between candidate (shadow) and production, the canary/shadow gate of 16-mlops section 6."""

import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pandas as pd
import pytest
from prometheus_client import REGISTRY

from shp_ml import synth
from shp_ml.drift import psi
from shp_ml.features import FEATURES, training_frame
from shp_ml.metrics import SHADOW_SCORE_PSI
from shp_ml.registry import FileRegistry
from shp_ml.serving import ModelHolder

METRIC = "shp_ml_shadow_score_psi"


def exported(version: str) -> float | None:
    return REGISTRY.get_sample_value(METRIC, {"model_version": version})


@pytest.fixture(autouse=True)
def clean_gauge() -> None:
    SHADOW_SCORE_PSI.clear()


@pytest.fixture(scope="module")
def rows() -> list[dict[str, Any]]:
    frame = training_frame(synth.generate(rows=1500, patients=800, seed=7))
    return [{str(k): v for k, v in r.items()} for r in frame[FEATURES].to_dict("records")]


@pytest.fixture
def same_model(registry: FileRegistry) -> Iterator[str]:
    """A third version with exactly the production model text: its scores must match production."""
    prod = registry.alias_version("production")
    assert prod is not None
    src = registry.root / "models" / prod
    meta = json.loads((src / "meta.json").read_text(encoding="utf-8"))
    version = registry.register((src / "model.txt").read_text(encoding="utf-8"), meta, "same model")
    yield version
    registry.set_alias("candidate", "2")


def run(holder: ModelHolder, rows: list[dict[str, Any]]) -> list[tuple[float, float]]:
    pairs = []
    for r in rows:
        prod = holder.score(r)
        assert prod is not None
        cand = holder.shadow(r, prod)
        assert cand is not None
        pairs.append((prod.score, cand.score))
    return pairs


def test_psi_is_exported_only_after_enough_pairs_and_matches_the_detector(
    registry: FileRegistry, rows: list[dict[str, Any]]
) -> None:
    registry.set_alias("candidate", "2")
    holder = ModelHolder(registry, shadow_enabled=True, psi_window=400, psi_min_pairs=100)
    assert holder.refresh() is True
    pairs = run(holder, rows[:99])
    assert exported("2") is None  # 99 pairs: too few for a stable PSI
    pairs += run(holder, rows[99:100])
    assert exported("2") is not None
    pairs += run(holder, rows[100:450])  # last recompute at pair 450 (a multiple of PSI_EVERY), window = last 400
    window = pairs[-400:]
    expected = psi(pd.Series([p for p, _ in window]), pd.Series([c for _, c in window]))
    assert exported("2") == pytest.approx(round(expected, 6))
    assert expected > 0  # two models trained on different seeds do not score identically


def test_same_model_gives_psi_near_zero(registry: FileRegistry, rows: list[dict[str, Any]], same_model: str) -> None:
    registry.set_alias("candidate", same_model)
    holder = ModelHolder(registry, shadow_enabled=True, psi_window=300, psi_min_pairs=300)
    holder.refresh()
    run(holder, rows[:300])
    value = exported(same_model)
    assert value is not None and value < 0.01  # far below the 0.1 gate


def test_removed_candidate_stops_exporting_psi(registry: FileRegistry, rows: list[dict[str, Any]]) -> None:
    registry.set_alias("candidate", "2")
    holder = ModelHolder(registry, shadow_enabled=True, psi_window=200, psi_min_pairs=50)
    holder.refresh()
    run(holder, rows[:50])
    assert exported("2") is not None
    aliases = registry.root / "aliases.json"
    data = json.loads(aliases.read_text(encoding="utf-8"))
    data.pop("candidate")
    aliases.write_text(json.dumps(data), encoding="utf-8")
    try:
        assert holder.refresh() is True
        assert holder.candidate() is None
        assert exported("2") is None  # a stale candidate must not keep passing the gate
    finally:
        registry.set_alias("candidate", "2")


def test_window_settings_are_validated(tmp_path: Path) -> None:
    with pytest.raises(ValueError):
        ModelHolder(FileRegistry(tmp_path), psi_window=10, psi_min_pairs=20)


def test_counter_series_exist_at_zero_once_a_version_is_loaded(registry: FileRegistry) -> None:
    """The canary error-rate and highRisk queries need a 0 series for a version that has no error yet."""
    registry.set_alias("candidate", "2")
    holder = ModelHolder(registry, shadow_enabled=True)
    holder.refresh()
    for version in ("1", "2"):
        assert REGISTRY.get_sample_value("shp_ml_predict_errors_total", {"model_version": version}) is not None
        for flag in ("true", "false"):
            labels = {"model_version": version, "high_risk": flag}
            assert REGISTRY.get_sample_value("shp_ml_high_risk_total", labels) is not None
    agree = {"model_version": "2", "agree": "false"}
    assert REGISTRY.get_sample_value("shp_ml_shadow_agreement_total", agree) is not None
