"""Copy the shared Python library into each Python service.

Each service's build context is only services/<svc>, so the libs/python/shp_platform library and the event schemas
(contracts/events) are copied into services/<svc>/src/shp_platform. Do not edit the copies by hand.

  python tools/pylib/sync.py           # copy
  python tools/pylib/sync.py --check   # CI: fail if a copy drifts from the source
"""

import filecmp
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LIB = ROOT / "libs" / "python" / "shp_platform"
SCHEMAS = ROOT / "contracts" / "events"
SERVICES = ["ai", "ml"]
HEADER = "COPIED FILE: edit libs/python/shp_platform then run python tools/pylib/sync.py\n"


def build(dest: Path) -> None:
    shutil.copytree(LIB, dest, ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "schemas"))
    for src in SCHEMAS.rglob("*.json"):
        if src.name.endswith(".example.json"):
            continue
        out = dest / "schemas" / src.relative_to(SCHEMAS)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(src.read_bytes())
    (dest / "COPIED_FROM_LIBS.txt").write_bytes(HEADER.encode("utf-8"))  # LF on every OS


def same(a: Path, b: Path) -> bool:
    cmp = filecmp.dircmp(a, b, ignore=["__pycache__"])
    if cmp.left_only or cmp.right_only or cmp.funny_files:
        return False
    _, mismatch, errors = filecmp.cmpfiles(a, b, cmp.common_files, shallow=False)
    return not mismatch and not errors and all(same(a / d, b / d) for d in cmp.common_dirs)


def main() -> int:
    check = "--check" in sys.argv
    with tempfile.TemporaryDirectory() as tmp:
        fresh = Path(tmp) / "shp_platform"
        build(fresh)
        bad = []
        for svc in SERVICES:
            target = ROOT / "services" / svc / "src" / "shp_platform"
            if check:
                if not target.is_dir() or not same(fresh, target):
                    bad.append(str(target.relative_to(ROOT)))
                continue
            shutil.rmtree(target, ignore_errors=True)
            shutil.copytree(fresh, target)
            print(f"synced {target.relative_to(ROOT)}")
        if bad:
            print("Out of sync with the source, run python tools/pylib/sync.py: " + ", ".join(bad))
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
