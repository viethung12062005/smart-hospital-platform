"""Real infrastructure for integration tests (MongoDB replica set, RabbitMQ) via testcontainers. Test use only.

If SHP_TEST_MONGO_URI / SHP_TEST_RABBITMQ_URL is set, use it directly (for example the local cluster from
`make -C dev up`); without Docker the tests are skipped (pytest.skip), not failed.

SHP_TEST_MONGO_IMAGE overrides the MongoDB image used for the test container (default `mongo:8`, matching
the Java convention — see libs/java/shp-data-mongo/.../MongoTestBase.java).
"""

import os
import time
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any


def docker_available() -> bool:
    try:
        import docker

        docker.from_env().ping()
        return True
    except Exception:  # noqa: BLE001
        return False


def _require_running(c: Any) -> None:
    """If the container has exited, report an error with its logs (instead of a cryptic exec error)."""
    w = c.get_wrapped_container()
    w.reload()
    if w.status != "running":
        logs = w.logs(tail=40).decode(errors="replace")
        raise RuntimeError(f"container {w.image.tags} stopped ({w.status}):\n{logs}")


def _skip(reason: str) -> None:
    import pytest

    pytest.skip(reason)


@contextmanager
def mongo_replica_set() -> Iterator[str]:
    if os.environ.get("SHP_TEST_MONGO_URI"):
        yield os.environ["SHP_TEST_MONGO_URI"]
        return
    if not docker_available():
        _skip("Docker or SHP_TEST_MONGO_URI is required")
    from pymongo import MongoClient
    from testcontainers.core.container import DockerContainer

    mongo_image = os.environ.get("SHP_TEST_MONGO_IMAGE", "mirror.gcr.io/library/mongo:8")
    c = (
        DockerContainer(mongo_image)
        # Kernel 6.19-7.0.13: tcmalloc violates the rseq ABI and mongod 8 blocks its own startup (SERVER-121912);
        # community-confirmed workaround: https://github.com/kiwiproject/kiwi/issues/1480. Same as MongoTestBase.java.
        .with_env("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
        .with_command(["mongod", "--replSet", "rs0", "--bind_ip_all"])
        .with_exposed_ports(27017)
    )
    c.start()
    try:
        for _ in range(60):
            _require_running(c)
            code, _out = c.exec(["mongosh", "--quiet", "--eval", "try{rs.status().ok}catch(e){rs.initiate().ok}"])
            if code == 0:
                break
            time.sleep(1)
        uri = f"mongodb://{c.get_container_host_ip()}:{c.get_exposed_port(27017)}/?directConnection=true"
        for _ in range(60):
            try:
                hello: dict[str, Any] = MongoClient(uri, serverSelectionTimeoutMS=1000).admin.command("hello")
                if hello.get("isWritablePrimary"):
                    break
            except Exception:  # noqa: BLE001, S110 - waiting for the replica set to be ready
                pass
            time.sleep(1)
        yield uri
    finally:
        c.stop()


@contextmanager
def rabbitmq() -> Iterator[str]:
    if os.environ.get("SHP_TEST_RABBITMQ_URL"):
        yield os.environ["SHP_TEST_RABBITMQ_URL"]
        return
    if not docker_available():
        _skip("Docker or SHP_TEST_RABBITMQ_URL is required")
    import pika
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

    client: Any = MongoClient(uri, tz_aware=True)
    client.drop_database(name)
    return client, client[name]
