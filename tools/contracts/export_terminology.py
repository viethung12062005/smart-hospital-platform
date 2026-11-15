"""Export the shared code systems to JSON so services can load them (ARC-20).

CLN-16/CLN-18/CLN-25 and the AI service read this JSON instead of the YAML: one file per code
system under contracts/terminology/dist/<name>.json shaped as {"url": ..., "concepts": {code: label}}.

    python -I tools/contracts/export_terminology.py           # regenerate dist/
    python -I tools/contracts/export_terminology.py --check   # CI: fail when dist/ is stale
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "contracts/terminology/code-systems.yaml"
DIST = ROOT / "contracts/terminology/dist"


def render() -> dict[str, str]:
    data = yaml.safe_load(SRC.read_text(encoding="utf-8"))
    out: dict[str, str] = {}
    for name, spec in data["codeSystems"].items():
        payload = {"url": spec["url"], "concepts": {str(k): str(v) for k, v in spec["concepts"].items()}}
        out[f"{name}.json"] = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    return out


def main() -> int:
    check = "--check" in sys.argv[1:]
    files = render()
    errors: list[str] = []
    DIST.mkdir(exist_ok=True)
    for name, content in files.items():
        path = DIST / name
        if check:
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                errors.append(f"{path.relative_to(ROOT)} differs from source: run tools/contracts/export_terminology.py")
        else:
            path.write_text(content, encoding="utf-8")
    stale = {p.name for p in DIST.glob("*.json")} - set(files)
    if stale:
        errors.append(f"dist/ has stale files: {', '.join(sorted(stale))}")
    for e in errors:
        print("ERROR", e)
    if errors:
        return 1
    print(("OK (check): " if check else "OK: ") + f"{len(files)} code systems -> contracts/terminology/dist/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
