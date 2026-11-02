import time
import uuid
from collections.abc import Callable, Iterator
from typing import Any

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from shp_ai import api_booking, api_summary, usage
from shp_ai.assistant import conversations
from shp_ai.blobstore import MemoryBlobStore
from shp_ai.deps import Deps
from shp_ai.directory import StaticDirectory
from shp_ai.kb.embedding import HashingEmbedder
from shp_ai.kb.index import MemoryIndex
from shp_ai.kb.service import ensure_indexes
from shp_ai.llm_config import DEFAULT, build_router
from shp_ai.main import build_app
from shp_ai.settings import Settings
from shp_platform import idempotency
from shp_platform.llm import FakeProvider, Provider
from shp_platform.security import Authenticator, AuthSettings
from shp_platform.testing import fresh_db, mongo_replica_set

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
ISS = "http://localhost:18080/realms/shp"
AUTH = Authenticator(AuthSettings(authority=ISS, audience="shp-api"), lambda _t: KEY.public_key())


def bearer(**claims: Any) -> dict[str, str]:
    now = int(time.time())
    body: dict[str, Any] = {"iss": ISS, "aud": "shp-api", "sub": "u1", "iat": now, "exp": now + 300, **claims}
    if set(body.get("roles", [])) & {"doctor", "nurse", "admin", "dpo"}:
        body.setdefault("amr", ["pwd", "mfa"])
    return {"Authorization": f"Bearer {jwt.encode(body, KEY, algorithm='RS256')}"}


DOCTOR: dict[str, Any] = {"sub": "dr-cardio", "roles": ["doctor"]}
NURSE: dict[str, Any] = {"sub": "nurse-1", "roles": ["nurse"]}
ADMIN: dict[str, Any] = {"sub": "admin-1", "roles": ["admin"]}
PATIENT: dict[str, Any] = {"sub": "patient-1", "roles": ["patient"]}


@pytest.fixture(scope="session")
def mongo_uri() -> Iterator[str]:
    with mongo_replica_set() as uri:
        yield uri


def fake_providers(**overrides: FakeProvider) -> list[Provider]:
    base = {
        "deepseek": FakeProvider("deepseek"),
        "gemini": FakeProvider("gemini"),
        "ollama": FakeProvider("ollama", external=False),
    }
    base.update(overrides)
    return list(base.values())


@pytest.fixture()
def make_deps(mongo_uri: str) -> Callable[..., Deps]:
    def make(providers: list[Provider] | None = None, directory: StaticDirectory | None = None, **extras: Any) -> Deps:
        name = f"ai_{uuid.uuid4().hex[:8]}"
        client, db = fresh_db(mongo_uri, name)
        ensure_indexes(db)
        conversations.ensure_indexes(db)
        usage.ensure_indexes(db)
        idempotency.ensure_indexes(db)
        api_summary.ensure_indexes(db)
        api_booking.ensure_indexes(db)
        settings = Settings(mongodb_db=name)
        deps = Deps(
            settings,
            client,
            MemoryBlobStore(),
            MemoryIndex(),
            HashingEmbedder(),
            directory
            or StaticDirectory(
                {"dr-cardio": ("cardiology",), "nurse-1": ("cardiology",)},
                {("dr-cardio", "11111111-1111-4111-8111-111111111111")},
            ),
            build_router(DEFAULT, providers or fake_providers(), None),
            {"llm_config": DEFAULT, **extras},
        )
        return deps

    return make


@pytest.fixture()
def api(make_deps: Callable[..., Deps]) -> Callable[..., tuple[TestClient, Deps]]:
    def make(**kw: Any) -> tuple[TestClient, Deps]:
        from shp_ai import features  # noqa: F401

        deps = make_deps(**kw)
        return TestClient(build_app(lambda: deps, AUTH)), deps

    return make
