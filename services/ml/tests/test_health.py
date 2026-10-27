import pytest
from fastapi.testclient import TestClient

from shp_ml.main import app

client = TestClient(app)


@pytest.mark.parametrize("path", ["/health/live", "/health/ready", "/info"])
def test_platform_endpoints_return_ok(path: str) -> None:
    assert client.get(path).status_code == 200
