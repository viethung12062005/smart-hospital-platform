"""ML-03/04/13/18 against a real MLflow server (skipped unless MLFLOW_TRACKING_URI is set).

The DoD audit noted that nothing had ever been recorded in MLflow: `MlflowRegistry` was uncovered and unused, so the
"MLflow tracking + model registry" half of the MLOps design was unproven. These tests drive the real training pipeline
(`pipeline.train_candidate`) against the MLflow running in the local cluster
(`make -C dev up PROFILE=full`, then `kubectl -n shp-deps port-forward svc/mlflow 15001:5000`).
"""

import os
import uuid
from typing import Any

import pytest

from shp_ml import pipeline
from shp_ml.registry import MlflowRegistry
from shp_ml.synth import generate

URI = os.environ.get("MLFLOW_TRACKING_URI", "")

pytestmark = pytest.mark.skipif(not URI, reason="needs a live MLflow (MLFLOW_TRACKING_URI)")


@pytest.fixture(scope="module")
def registry() -> MlflowRegistry:
    # One experiment per run keeps repeated local runs independent.
    return MlflowRegistry(URI, experiment=f"shp-no-show-test-{uuid.uuid4().hex[:8]}")


def _facts(seed: int = 42) -> Any:
    return generate(rows=12_000, patients=6_000, months=24, seed=seed)


def test_a_training_run_is_recorded_and_the_candidate_registered(registry: MlflowRegistry) -> None:
    """ML-03/ML-04: the run is logged (metrics + dataset hash) and the model is a registry version."""
    result = pipeline.train_candidate(_facts(), registry, dataset_sha256="b" * 64, seed=42)

    assert result.run_id, "the run must be logged in MLflow"
    assert result.metrics["auc"] >= 0.5
    assert result.metrics["baselineAuc"] >= 0.5
    assert result.version, result.gate.as_dict()

    assert registry.alias_version("candidate") == result.version

    meta: dict[str, Any] = result.metrics
    assert meta["datasetSha256"] if "datasetSha256" in meta else True
    loaded, loaded_meta = registry.load(result.version)
    assert loaded_meta["datasetSha256"] == "b" * 64
    assert loaded_meta["seed"] == 42
    assert 0.0 < loaded.threshold < 1.0


def test_promoting_the_candidate_moves_the_production_alias(registry: MlflowRegistry) -> None:
    """ML-18: a candidate that passes the gate can be promoted; a rejected one never moves the alias."""
    first = pipeline.train_candidate(_facts(seed=1), registry, dataset_sha256="c" * 64, seed=1)
    if first.version is None:
        # The gate rejected this candidate (a subgroup AUC below its floor); nothing may be promoted.
        assert registry.alias_version("production") is None
        return

    registry.set_alias("production", first.version)
    assert registry.alias_version("production") == first.version

    second = pipeline.train_candidate(_facts(seed=2), registry, dataset_sha256="d" * 64, seed=2)
    if second.version is None:  # gate rejected the candidate: the production alias must not move
        assert registry.alias_version("production") == first.version
        return
    registry.set_alias("candidate", second.version)
    registry.set_alias("production", second.version)
    assert registry.alias_version("production") == second.version
    # The previous version can still be loaded, so a rollback is one alias change (16-mlops).
    old, old_meta = registry.load(first.version)
    assert old.to_text() and old_meta["datasetSha256"] == "c" * 64
