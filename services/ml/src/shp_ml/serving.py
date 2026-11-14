"""Keeps the serving model and reloads it when the alias changes (ML-09), shadow mode (ML-24), per-version metrics
(ML-25).

- `production` answers; if `candidate` differs from `production` and shadow is enabled, the candidate scores in
  parallel: the result is only recorded (metrics, prediction marked as shadow) and never used for reminders.
  Candidate errors have no effect.
- Polls the registry every 30 seconds; a new model replaces the old one only once fully loaded (atomic reference
  swap), no redeploy needed.
"""

import logging
import threading
import time
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from shp_ml.metrics import MODEL_INFO, PREDICT_ERRORS, PREDICT_LATENCY, SCORE_HIST, SHADOW_AGREE
from shp_ml.model import NoShowModel
from shp_ml.registry import Registry

LOG = logging.getLogger("shp.ml.serving")


@dataclass(frozen=True)
class Scored:
    score: float
    threshold: float
    high_risk: bool
    model_version: str
    latency_ms: int


class ModelHolder:
    def __init__(self, registry: Registry | None, shadow_enabled: bool = True) -> None:
        self._registry = registry
        self._shadow_enabled = shadow_enabled
        self._lock = threading.Lock()
        self._production: tuple[NoShowModel, dict[str, Any]] | None = None
        self._candidate: NoShowModel | None = None

    def refresh(self) -> bool:
        """Read the aliases; load a new model if it changed. Returns True if anything changed. Registry errors keep
        the running model."""
        if self._registry is None:
            return False
        try:
            prod_v = self._registry.alias_version("production")
            cand_v = self._registry.alias_version("candidate")
            changed = False
            cur = self._production
            if prod_v is not None and (cur is None or cur[0].version != prod_v):
                loaded = self._registry.load(prod_v)
                with self._lock:
                    self._production = loaded
                MODEL_INFO.labels(model_version=prod_v, role="production").set(1)
                LOG.info("loaded production model %s", prod_v)
                changed = True
            want_cand = cand_v if (self._shadow_enabled and cand_v is not None and cand_v != prod_v) else None
            have_cand = self._candidate.version if self._candidate else None
            if want_cand != have_cand:
                cand = self._registry.load(want_cand)[0] if want_cand else None
                with self._lock:
                    self._candidate = cand
                changed = True
            return changed
        except Exception as exc:  # noqa: BLE001 - registry error: keep the running model
            LOG.warning("cannot read registry: %s", type(exc).__name__)
            return False

    def production(self) -> tuple[NoShowModel, dict[str, Any]] | None:
        with self._lock:
            return self._production

    def candidate(self) -> NoShowModel | None:
        with self._lock:
            return self._candidate

    def score(self, features: Mapping[str, Any]) -> Scored | None:
        prod = self.production()
        if prod is None:
            return None
        model = prod[0]
        t0 = time.perf_counter()
        try:
            value = model.score(features)
        except Exception:
            PREDICT_ERRORS.labels(model_version=model.version).inc()
            raise
        elapsed = time.perf_counter() - t0
        PREDICT_LATENCY.labels(model_version=model.version).observe(elapsed)
        SCORE_HIST.labels(model_version=model.version).observe(value)
        return Scored(round(value, 6), model.threshold, value >= model.threshold, model.version, int(elapsed * 1000))

    def shadow(self, features: Mapping[str, Any], production: Scored) -> Scored | None:
        cand = self.candidate()
        if cand is None:
            return None
        t0 = time.perf_counter()
        try:
            value = cand.score(features)
        except Exception:  # noqa: BLE001 - a shadow error never affects the main path
            PREDICT_ERRORS.labels(model_version=cand.version).inc()
            return None
        elapsed = time.perf_counter() - t0
        PREDICT_LATENCY.labels(model_version=cand.version).observe(elapsed)
        SCORE_HIST.labels(model_version=cand.version).observe(value)
        high = value >= cand.threshold
        SHADOW_AGREE.labels(model_version=cand.version, agree=str(high == production.high_risk).lower()).inc()
        return Scored(round(value, 6), cand.threshold, high, cand.version, int(elapsed * 1000))


def start_poller(holder: ModelHolder, interval: float, stop: threading.Event) -> threading.Thread:
    def loop() -> None:
        while not stop.wait(interval):
            holder.refresh()

    t = threading.Thread(target=loop, name="model-poller", daemon=True)
    t.start()
    return t
