"""The training image definition (MLO-02): everything that makes a run reproducible is pinned in files we can check."""

import re
import string
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCKERFILE = (ROOT / "Dockerfile.train").read_text(encoding="utf-8")
LOCK = (ROOT / "requirements-train.lock").read_text(encoding="utf-8")
HASH_PREFIX = "--hash=sha256:"


def _direct_requirements() -> dict[str, str]:
    out: dict[str, str] = {}
    for line in (ROOT / "requirements-train.in").read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            name, spec = re.split(r"(?=[<>=!~])", line.strip(), maxsplit=1)
            out[name] = spec
    return out


def test_base_image_is_pinned_by_digest_in_both_stages() -> None:
    froms = re.findall(r"^FROM (\S+)", DOCKERFILE, flags=re.MULTILINE)
    assert len(froms) == 2 and all(re.search(r"@sha256:[0-9a-f]{64}$", f) for f in froms)
    serving = (ROOT / "Dockerfile").read_text(encoding="utf-8")
    assert "libgomp1=" in DOCKERFILE and "libgomp1=" in serving  # same system packages as serving


def test_dependencies_are_installed_only_from_the_hash_locked_file() -> None:
    assert "--require-hashes" in DOCKERFILE and "--no-deps" in DOCKERFILE and "requirements-train.lock" in DOCKERFILE
    pins = re.findall(r"^[A-Za-z0-9._-]+==", LOCK, flags=re.MULTILINE)
    assert len(pins) > 20
    blocks = re.split(r"^(?=[A-Za-z0-9._-]+==)", LOCK, flags=re.MULTILINE)[1:]
    assert len(blocks) == len(pins)
    for block in blocks:  # every pin carries at least one hash
        digests = [
            line.strip().removeprefix(HASH_PREFIX)
            for line in block.splitlines()
            if line.strip().startswith(HASH_PREFIX)
        ]
        assert any(len(d) == 64 and all(c in string.hexdigits for c in d) for d in digests), block.splitlines()[0]
    # Every top-level line is a pin (`name==version`), never a range: a lock file that still had
    # `>=`/`~=` would let pip resolve something other than what was verified.
    for line in LOCK.splitlines():
        if not line or line.startswith((" ", "#")):
            continue  # the hash lines and "# via" comments that continue a pin
        name, separator, _ = line.partition("==")
        assert separator and name and all(c.isalnum() or c in "._-" for c in name), f"not a pin: {line}"


def test_lock_covers_the_declared_training_dependencies_with_the_same_ranges() -> None:
    project = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["project"]
    declared = [
        *project["dependencies"],
        *project["optional-dependencies"]["train"],
        *project["optional-dependencies"]["mlflow"],
    ]
    ranges = {m.group(1): m.group(2) for d in declared if (m := re.match(r"([A-Za-z0-9._-]+)(?:\[.*\])?(.*)", d))}
    direct = _direct_requirements()
    for name, spec in direct.items():
        assert ranges[name] == spec, f"{name}: requirements-train.in says {spec}, pyproject.toml says {ranges[name]}"
        assert re.search(rf"^{re.escape(name)}==", LOCK, flags=re.MULTILINE | re.IGNORECASE)


def test_training_environment_is_deterministic_and_unprivileged() -> None:
    for setting in ("PYTHONHASHSEED=0", "OMP_NUM_THREADS=1", "OPENBLAS_NUM_THREADS=1", "MKL_NUM_THREADS=1", "TZ=UTC"):
        assert setting in DOCKERFILE
    assert "USER 10001" in DOCKERFILE
