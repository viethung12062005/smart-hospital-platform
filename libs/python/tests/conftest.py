import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]

# Test chạy trên nguồn libs/python: chép lược đồ event vào package (thư mục bị .gitignore) như sync.py làm cho service.
_dest = Path(__file__).resolve().parents[1] / "shp_platform" / "schemas"
if _dest.exists():
    shutil.rmtree(_dest)
for _src in (ROOT / "contracts" / "events").rglob("*.json"):
    if not _src.name.endswith(".example.json"):
        _out = _dest / _src.relative_to(ROOT / "contracts" / "events")
        _out.parent.mkdir(parents=True, exist_ok=True)
        _out.write_bytes(_src.read_bytes())


# ---- hạ tầng thật cho test tích hợp (Docker); không có Docker thì bỏ qua các test này ----
import os  # noqa: E402
import time  # noqa: E402
from collections.abc import Iterator  # noqa: E402
from typing import Any  # noqa: E402

import pytest  # noqa: E402


def _docker_ok() -> bool:
    try:
        import docker  # type: ignore[import-untyped]

        docker.from_env().ping()
        return True
    except Exception:  # noqa: BLE001
        return False


@pytest.fixture(scope="session")
def mongo_uri() -> Iterator[str]:
    if os.environ.get("SHP_TEST_MONGO_URI"):
        yield os.environ["SHP_TEST_MONGO_URI"]
        return
    if not _docker_ok():
        pytest.skip("cần Docker hoặc SHP_TEST_MONGO_URI")
    from testcontainers.core.container import DockerContainer

    c = (
        DockerContainer("mirror.gcr.io/library/mongo:8.0")
        .with_command("--replSet rs0 --bind_ip_all")
        .with_exposed_ports(27017)
    )
    c.start()
    try:
        for _ in range(60):
            code, _out = c.exec(["mongosh", "--quiet", "--eval", "try{rs.status().ok}catch(e){rs.initiate().ok}"])
            if code == 0:
                break
            time.sleep(1)
        host, port = c.get_container_host_ip(), c.get_exposed_port(27017)
        uri = f"mongodb://{host}:{port}/?directConnection=true"
        from pymongo import MongoClient

        for _ in range(60):
            try:
                if MongoClient(uri, serverSelectionTimeoutMS=1000).admin.command("hello").get("isWritablePrimary"):
                    break
            except Exception:  # noqa: BLE001, S110 - đang chờ replica set sẵn sàng
                pass
            time.sleep(1)
        yield uri
    finally:
        c.stop()


@pytest.fixture(scope="session")
def rabbit_url() -> Iterator[str]:
    if os.environ.get("SHP_TEST_RABBITMQ_URL"):
        yield os.environ["SHP_TEST_RABBITMQ_URL"]
        return
    if not _docker_ok():
        pytest.skip("cần Docker hoặc SHP_TEST_RABBITMQ_URL")
    from testcontainers.core.container import DockerContainer

    c = (
        DockerContainer("mirror.gcr.io/library/rabbitmq:4.1")
        .with_env("RABBITMQ_DEFAULT_USER", "shp")
        .with_env("RABBITMQ_DEFAULT_PASS", "shp-test")
        .with_exposed_ports(5672)
    )
    c.start()
    try:
        url = f"amqp://shp:shp-test@{c.get_container_host_ip()}:{c.get_exposed_port(5672)}/%2F"
        import pika

        for _ in range(90):
            try:
                pika.BlockingConnection(pika.URLParameters(url)).close()
                break
            except Exception:  # noqa: BLE001
                time.sleep(1)
        yield url
    finally:
        c.stop()


def fresh_db(uri: str, name: str) -> Any:
    from pymongo import MongoClient

    client: Any = MongoClient(uri)
    client.drop_database(name)
    return client, client[name]
