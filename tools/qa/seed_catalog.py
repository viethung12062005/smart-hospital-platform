"""Create test case stubs for every SRS acceptance criterion and NFR that has none yet.

Usage: python -I tools/qa/seed_catalog.py [--write]

Existing test cases are never changed; only missing ones are appended to catalog/<module>.yaml. Automation status is NOT
set here (it lives in catalog/automation.yaml), so seeding is safe to run whenever the SRS gains requirements.
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I removes the script directory

import yaml  # noqa: E402

from common import CATALOG, P0_RE, ROOT, load_catalog, load_srs

NFR_LEVEL = {
    "Load test": "performance", "AI eval": "ai-eval", "Security": "security", "Integration": "integration",
    "Failure test": "resilience", "DR drill": "resilience", "Monitoring": "monitoring", "Review": "review",
    "E2E": "e2e", "DAST": "security", "Contract": "contract", "CI": "ci", "API": "api", "Unit": "unit",
    "Config scan": "security", "Monitoring ": "monitoring",
}


def ac_level(module: str, text: str) -> str:
    t = text.lower()
    if module == "ai" and any(k in t for k in ("trích dẫn", "mô hình", "model", "prompt", "tóm tắt", "bot", "trợ lý")):
        return "ai-eval"
    if any(k in t for k in ("giao diện", "màn hình", "hiển thị", "nút ", "form")):
        return "e2e"
    if any(k in t for k in ("khôi phục", "failover", "diễn tập")):
        return "resilience"
    if module == "system" or any(k in t for k in ("sự kiện", "job", "hàng đợi", "worker")):
        return "integration"
    return "api"


def main() -> int:
    write = "--write" in sys.argv
    m = load_srs()
    have = load_catalog()
    new: dict[str, list[dict]] = {}
    seen: set[str] = set()
    for module, _section, uc in m.use_cases():
        uc_label = m.num[("UC", uc["key"])]
        for ac_id, text in uc.get("acceptance", []):
            tc_id = f"TC-{ac_id}"
            if tc_id in seen or any(h.get("srs_uc") == uc["key"] and h.get("covers", [None])[0] == ac_id for h in have.values()):
                tc_id = f"TC-{ac_id}-{uc['key']}"
            seen.add(tc_id)
            if tc_id in have:
                continue
            secure = bool(P0_RE.search(text)) or any(k.startswith(("NFR-SEC", "BR-06", "BR-07", "BR-05")) for k in uc["trace"])
            new.setdefault(module["key"], []).append({
                "id": tc_id,
                "title": f"{uc['name']}: {ac_id}",
                "covers": [ac_id],
                "srs_uc": uc["key"],
                "srs_ref": uc_label,
                "module": module["key"],
                "level": ac_level(module["key"], text),
                "priority": "P0" if secure else "P1",
                "spec": text,
            })
    for cat in m["nfr"]["categories"]:
        for item in cat["items"]:
            tc_id = f"TC-{item['id']}"
            if tc_id in have:
                continue
            level = NFR_LEVEL.get(item["test"].split(",")[0].strip(), "review")
            new.setdefault("nfr", []).append({
                "id": tc_id,
                "title": f"{item['id']} {item['title']}",
                "covers": [item["id"]],
                "module": "nfr",
                "level": level,
                "priority": "P0" if item["id"].startswith("NFR-SEC") or item["id"] in ("NFR-A4", "NFR-A5") else "P1",
                "spec": f"Chỉ số: {item['target']}. Cách đo: {item['measure']}",
            })
    total = sum(len(v) for v in new.values())
    print(f"existing: {len(have)}, new stubs: {total}")
    for mod, cases in new.items():
        print(f"  {mod}: {len(cases)}")
    if not write:
        print("dry run; pass --write to append")
        return 0
    for mod, cases in new.items():
        path = os.path.join(CATALOG, f"{mod}.yaml")
        doc = {"testcases": []}
        if os.path.exists(path):
            with open(path, encoding="utf-8") as fh:
                doc = yaml.safe_load(fh) or doc
        doc["testcases"].extend(cases)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(f"# Test case của module '{mod}'. Nguồn: tiêu chí nghiệm thu / NFR trong docs/02-requirements/srs. Tự động hóa: xem automation.yaml\n")
            yaml.safe_dump(doc, fh, allow_unicode=True, sort_keys=False, width=120)
    print("written to", os.path.relpath(CATALOG, ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
