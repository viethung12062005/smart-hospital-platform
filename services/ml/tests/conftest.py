import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from shp_ml import synth
from shp_ml.card import model_card
from shp_ml.features import time_split, training_frame
from shp_ml.pipeline import train_lightgbm
from shp_ml.registry import FileRegistry
from shp_platform.security import Authenticator, AuthSettings
from shp_platform.testing import mongo_replica_set

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
ISS = "http://localhost:18080/realms/shp"
AUTH = Authenticator(AuthSettings(authority=ISS, audience="shp-api"), lambda _t: KEY.public_key())


def token(**claims: Any) -> str:
    now = int(time.time())
    base: dict[str, Any] = {"iss": ISS, "aud": "shp-api", "sub": "u1", "iat": now, "exp": now + 300}
    base.update(claims)
    return jwt.encode(base, KEY, algorithm="RS256")


def bearer(**claims: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {token(**claims)}"}


@pytest.fixture(scope="session")
def mongo_uri() -> Iterator[str]:
    with mongo_replica_set() as uri:
        yield uri


def _register(reg: FileRegistry, seed: int) -> str:
    frame = training_frame(synth.generate(rows=6000, patients=3000, seed=seed))
    train, _ = time_split(frame)
    booster, params, threshold = train_lightgbm(train, seed)
    meta: dict[str, Any] = {
        "threshold": threshold,
        "datasetSha256": "0" * 64,
        "seed": seed,
        "params": params,
        "runId": f"test-{seed}",
        "registeredAt": "2026-11-01T00:00:00Z",
        "metrics": {
            "auc": 0.78,
            "baselineAuc": 0.76,
            "productionAuc": None,
            "ece": 0.02,
            "latencyP95Ms": 5.0,
            "threshold": threshold,
            "precisionAtThreshold": 0.5,
            "recallAtThreshold": 0.4,
            "subgroupAuc": {},
            "positiveRate": 0.18,
            "train": {"n": len(train), "from": "a", "to": "b"},
            "validation": {"n": 0, "from": "a", "to": "b"},
        },
        "gate": {"passed": True, "checks": []},
    }
    return reg.register(booster.model_to_string(), meta, model_card(meta))


@pytest.fixture(scope="session")
def registry(tmp_path_factory: pytest.TempPathFactory) -> FileRegistry:
    """Two small models: v1 production, v2 for testing shadow and alias changes."""
    reg = FileRegistry(Path(tmp_path_factory.mktemp("registry")))
    v1 = _register(reg, 1)
    _register(reg, 2)
    reg.set_alias("production", v1)
    return reg
