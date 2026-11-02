import pytest
from fastapi.testclient import TestClient

from shp_ai.main import app


@pytest.mark.parametrize("path", ["/health/live", "/health/ready", "/info", "/metrics"])
def test_platform_endpoints_return_ok(path: str) -> None:
    with TestClient(app) as client:
        assert client.get(path).status_code == 200
