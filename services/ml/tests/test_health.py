import pytest
from fastapi.testclient import TestClient

from shp_ml.main import app


@pytest.mark.parametrize("path", ["/health/live", "/health/ready", "/info", "/metrics"])
def test_platform_endpoints_return_ok(path: str) -> None:
    with TestClient(app) as client:
        assert client.get(path).status_code == 200


def test_tracing_is_installed() -> None:
    # LOC-04: FastAPIInstrumentor wraps app.build_middleware_stack; detailed behavior (traceparent continuation,
    # probe skipping, OTLP on/off) is checked in libs/python/tests/test_telemetry.py
    assert "build_middleware_stack" in app.__dict__
