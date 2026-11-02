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
