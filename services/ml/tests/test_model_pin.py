"""US-073 (MLO-03): a pod pinned to a model version, so Argo Rollouts can canary a model change and abort it."""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from shp_ml.main import State, build_app
from shp_ml.registry import FileRegistry
from shp_ml.serving import ModelHolder
from shp_ml.settings import Settings

from .conftest import AUTH


@pytest.fixture
def aliases(registry: FileRegistry) -> Iterator[FileRegistry]:
    """production = 1, candidate = 2 for the test; restored afterwards (the registry fixture is shared)."""
    before = (registry.root / "aliases.json").read_text(encoding="utf-8")
    registry.set_alias("production", "1")
    registry.set_alias("candidate", "2")
    yield registry
    (registry.root / "aliases.json").write_text(before, encoding="utf-8")


def served(holder: ModelHolder) -> str:
    prod = holder.production()
    assert prod is not None
    return prod[0].version


def test_pinned_pod_serves_its_version_and_ignores_the_production_alias(aliases: FileRegistry) -> None:
    holder = ModelHolder(aliases, shadow_enabled=True, serving_version="2")
    assert holder.refresh() is True
    assert served(holder) == "2"
    assert holder.candidate() is None  # candidate == served version: nothing to shadow
    aliases.set_alias("production", "2")
    aliases.set_alias("production", "1")  # an alias move by a person or a job does not change a pinned pod
    holder.refresh()
    assert served(holder) == "2"


def test_canary_and_stable_pods_during_a_model_canary(aliases: FileRegistry) -> None:
    """Stable pods keep the old version and shadow the candidate (they export the score PSI the analysis reads);
    canary pods serve the candidate. Aborting the Rollout leaves only the stable pods: the old model."""
    stable = ModelHolder(aliases, shadow_enabled=True, serving_version="1")
    canary = ModelHolder(aliases, shadow_enabled=True, serving_version="2")
    stable.refresh()
    canary.refresh()
    assert served(stable) == "1"
    cand = stable.candidate()
    assert cand is not None and cand.version == "2"
    assert served(canary) == "2" and canary.candidate() is None


def test_unpinned_pod_still_follows_the_production_alias(aliases: FileRegistry) -> None:
    holder = ModelHolder(aliases, serving_version=None)
    holder.refresh()
    assert served(holder) == "1" and holder.pinned_version is None
    aliases.set_alias("production", "2")
    assert holder.refresh() is True and served(holder) == "2"


@pytest.mark.parametrize(("pin", "status"), [("2", 200), ("99", 503), ("", 200)])
def test_readiness_waits_for_the_pinned_version(aliases: FileRegistry, pin: str, status: int) -> None:
    settings = Settings(serving_model_version=pin)
    holder = ModelHolder(aliases, serving_version=settings.serving_model_version or None)
    holder.refresh()  # version 99 does not exist: the registry error is logged and nothing is loaded
    app = build_app(lambda: State(settings, None, holder, aliases), AUTH, ["svc-scheduling"], start_background=False)
    with TestClient(app) as tc:
        assert tc.get("/health/ready").status_code == status
        assert tc.get("/health/live").status_code == 200  # never restarted for a missing model
