"""Chép thư viện Python dùng chung vào từng service Python.

Build context của mỗi service chỉ là services/<svc>, nên thư viện libs/python/shp_platform và lược đồ event
(contracts/events) được chép vào services/<svc>/src/shp_platform. Bản chép không được sửa tay.

  python tools/pylib/sync.py           # chép
  python tools/pylib/sync.py --check   # CI: báo lỗi nếu bản chép lệch nguồn
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
HEADER = "BẢN CHÉP: sửa ở libs/python/shp_platform rồi chạy python tools/pylib/sync.py\n"


def build(dest: Path) -> None:
    shutil.copytree(LIB, dest, ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "schemas"))
    for src in SCHEMAS.rglob("*.json"):
        if src.name.endswith(".example.json"):
            continue
        out = dest / "schemas" / src.relative_to(SCHEMAS)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(src.read_bytes())
    (dest / "COPIED_FROM_LIBS.txt").write_bytes(HEADER.encode("utf-8"))  # LF trên mọi hệ điều hành


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
            print("Lệch nguồn, chạy python tools/pylib/sync.py: " + ", ".join(bad))
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
