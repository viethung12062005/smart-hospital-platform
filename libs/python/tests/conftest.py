import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]

# Tests run against the libs/python source: copy the event schemas into the package (.gitignore'd directory) as
# sync.py does for services.
_dest = Path(__file__).resolve().parents[1] / "shp_platform" / "schemas"
if _dest.exists():
    shutil.rmtree(_dest)
for _src in (ROOT / "contracts" / "events").rglob("*.json"):
    if not _src.name.endswith(".example.json"):
        _out = _dest / _src.relative_to(ROOT / "contracts" / "events")
        _out.parent.mkdir(parents=True, exist_ok=True)
        _out.write_bytes(_src.read_bytes())


from collections.abc import Iterator  # noqa: E402

import pytest  # noqa: E402

from shp_platform.testing import mongo_replica_set, rabbitmq  # noqa: E402


@pytest.fixture(scope="session")
def mongo_uri() -> Iterator[str]:
    with mongo_replica_set() as uri:
        yield uri


@pytest.fixture(scope="session")
def rabbit_url() -> Iterator[str]:
    with rabbitmq() as url:
        yield url
