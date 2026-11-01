"""Requirement-to-test traceability: integrity checks for CI and generated reports.

Usage:
  python -I tools/qa/trace.py --check     # CI gate: fail when requirements lack test cases, references are broken, or
                                          # automation coverage drops below docs/06-testing/baseline.json
  python -I tools/qa/trace.py             # also write docs/06-testing/traceability.md and dist/Test_Cases.xlsx
  python -I tools/qa/trace.py --update-baseline   # raise the baseline after automation improves (reviewed in the PR)

Gate rules
  1. Every acceptance criterion (AC-*) and NFR in the SRS is covered by at least one test case, and every use case has one.
  2. Every test case covers existing requirements; ids are unique.
  3. Automation references (file::name) and E2E tags (TC-... in web/e2e test titles) point to real files / test cases.
  4. 'planned' test cases state what blocks them and who owns them (owner defaults to the module owner).
  5. Ratchet: the number of automated and partially automated P0 test cases never decreases.
"""
from __future__ import annotations

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I removes the script directory

from common import AUTOMATION, DIST, LEVELS, PRIORITIES, ROOT, TESTING, load_automation, load_catalog, load_srs  # noqa: E402

E2E_DIR = os.path.join(ROOT, "web", "e2e")
BASELINE = os.path.join(TESTING, "baseline.json")
OWNER_BY_MODULE = {
    "scheduling": "qnt124", "notification": "qnt124", "system": "qnt124", "clinical": "Her55chers", "documents": "Her55chers",
    "privacy-admin": "Her55chers", "ai": "24021501-hunglk", "nfr": "viethung12062005", "security": "viethung12062005",
}
TAG = re.compile(r"(TC-[A-Za-z0-9.]+(?:-[A-Za-z0-9.]+)*)")
TEST_CALL = re.compile(r"\btest(\.fixme|\.skip)?\(\s*[`'\"]([^`'\"]+)[`'\"]")


def e2e_tags(catalog) -> tuple[dict[str, list[dict]], list[str]]:
    """TC id -> [{file, title, fixme}] from web/e2e/*.spec.ts titles; plus errors for unknown ids."""
    found: dict[str, list[dict]] = {}
    errors: list[str] = []
    if not os.path.isdir(E2E_DIR):
        return found, errors
    for name in sorted(os.listdir(E2E_DIR)):
        if not name.endswith(".spec.ts"):
            continue
        text = open(os.path.join(E2E_DIR, name), encoding="utf-8").read()
        for m in TEST_CALL.finditer(text):
            mark, title = m.group(1), m.group(2)
            for raw in TAG.findall(title):
                tc = raw
                while tc not in catalog and "-" in tc:
                    tc = tc.rsplit("-", 1)[0]
                if tc not in catalog:
                    errors.append(f"web/e2e/{name}: '{raw}' is not a test case in the catalog")
                    continue
                found.setdefault(tc, []).append({"file": f"web/e2e/{name}", "title": title, "fixme": mark in (".fixme", ".skip")})
    return found, errors


def ref_exists(ref: str) -> str | None:
    path, _, name = ref.partition("::")
    full = os.path.join(ROOT, path)
    if not os.path.isfile(full):
        return f"file not found: {path}"
    if name and name not in open(full, encoding="utf-8", errors="replace").read():
        return f"'{name}' not found in {path}"
    return None


def analyse():
    m = load_srs()
    catalog = load_catalog()
    auto = load_automation()
    errors: list[str] = []

    # ---- requirements
    ac_to_uc: dict[str, set[str]] = {}
    for _mod, _sec, uc in m.use_cases():
        for ac_id, _text in uc.get("acceptance", []):
            ac_to_uc.setdefault(ac_id, set()).add(uc["key"])
    nfr_ids = {it["id"] for cat in m["nfr"]["categories"] for it in cat["items"]}
    req_ids = set(ac_to_uc) | nfr_ids
    uc_keys = {uc["key"] for _m, _s, uc in m.use_cases()}

    covered: set[str] = set()
    for tc_id, tc in catalog.items():
        for f in ("title", "covers", "module", "level", "priority", "spec"):
            if not tc.get(f):
                errors.append(f"{tc_id}: missing '{f}'")
        if tc.get("level") not in LEVELS:
            errors.append(f"{tc_id}: unknown level '{tc.get('level')}'")
        if tc.get("priority") not in PRIORITIES:
            errors.append(f"{tc_id}: unknown priority '{tc.get('priority')}'")
        for r in tc.get("covers", []):
            if r not in req_ids:
                errors.append(f"{tc_id}: covers unknown requirement '{r}'")
            covered.add(r)
    for r in sorted(req_ids - covered):
        errors.append(f"requirement {r} has no test case")
    uc_covered = {uc for r in covered for uc in ac_to_uc.get(r, ())}
    for uc in sorted(uc_keys - uc_covered):
        errors.append(f"use case '{uc}' has no test case")

    # ---- automation
    tags, tag_errors = e2e_tags(catalog)
    errors += tag_errors
    for tc_id in auto:
        if tc_id not in catalog:
            errors.append(f"automation.yaml: unknown test case {tc_id}")
    rows = []
    for tc_id, tc in catalog.items():
        entry = auto.get(tc_id, {})
        refs = list(entry.get("refs", []))
        live_e2e = [t for t in tags.get(tc_id, []) if not t["fixme"]]
        fixme_e2e = [t for t in tags.get(tc_id, []) if t["fixme"]]
        for r in refs:
            problem = ref_exists(r)
            if problem:
                errors.append(f"{tc_id}: {problem}")
        status = entry.get("status")
        gap = entry.get("gap") or entry.get("blocked_by") or ""
        if not status:
            if live_e2e:
                status, gap = "partial", "Chỉ kiểm ở giao diện với dữ liệu giả; chưa kiểm API/dịch vụ thật."
            elif tc["level"] in ("review", "manual", "monitoring"):
                status, gap = "manual", "Rà soát/đo thủ công theo kế hoạch kiểm thử."
            else:
                status, gap = "planned", "Chờ tính năng hoặc môi trường tương ứng được hiện thực."
        if status not in AUTOMATION:
            errors.append(f"{tc_id}: unknown automation status '{status}'")
        if status in ("automated", "partial") and not (refs or live_e2e):
            errors.append(f"{tc_id}: status '{status}' but no test reference")
        if status == "planned" and tc_id in auto and not entry.get("blocked_by"):
            errors.append(f"{tc_id}: 'planned' needs blocked_by")
        owner = entry.get("owner") or tc.get("owner") or OWNER_BY_MODULE.get(tc["module"], "viethung12062005")
        rows.append({**tc, "status": status, "gap": gap, "owner": owner, "refs": refs, "e2e": live_e2e, "e2e_fixme": fixme_e2e})

    # ---- ratchet
    p0 = [r for r in rows if r["priority"] == "P0"]
    stats = {
        "total": len(rows),
        "automated_or_partial": sum(r["status"] in ("automated", "partial") for r in rows),
        "p0_total": len(p0),
        "p0_automated_or_partial": sum(r["status"] in ("automated", "partial") for r in p0),
        "automated": sum(r["status"] == "automated" for r in rows),
    }
    if os.path.exists(BASELINE):
        base = json.load(open(BASELINE, encoding="utf-8"))
        for k in ("automated_or_partial", "p0_automated_or_partial", "automated"):
            if stats[k] < base.get(k, 0):
                errors.append(f"automation ratchet: {k} fell from {base[k]} to {stats[k]}")
    return m, rows, ac_to_uc, nfr_ids, stats, sorted(set(errors))


def write_reports(m, rows, ac_to_uc, nfr_ids, stats):
    from collections import Counter, defaultdict

    os.makedirs(DIST, exist_ok=True)
    by_req: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        for c in r["covers"]:
            by_req[c].append(r)
    lines = ["# Truy vết yêu cầu ↔ test case", "",
             "> Sinh tự động bằng `python -I tools/qa/trace.py`. Không sửa tay. Nguồn: `docs/06-testing/catalog/`, SRS v"
             f"{m['document']['version']}.", ""]
    lines += ["## Tóm tắt", "", "| Chỉ số | Giá trị |", "|---|---|",
              f"| Test case | {stats['total']} |",
              f"| Đã tự động hóa hoặc một phần | {stats['automated_or_partial']} ({stats['automated_or_partial'] * 100 // stats['total']}%) |",
              f"| Tự động hóa đầy đủ | {stats['automated']} |",
              f"| P0 (bảo mật, dữ liệu bệnh nhân, an toàn người bệnh) | {stats['p0_total']}, tự động hóa ít nhất một phần: {stats['p0_automated_or_partial']} |", ""]
    lines += ["## Theo module và trạng thái", "", "| Module | Tổng | Tự động | Một phần | Chờ viết | Thủ công |", "|---|---|---|---|---|---|"]
    for mod in sorted({r["module"] for r in rows}):
        c = Counter(r["status"] for r in rows if r["module"] == mod)
        lines.append(f"| {mod} | {sum(c.values())} | {c['automated']} | {c['partial']} | {c['planned']} | {c['manual']} |")
    lines += ["", "## Theo mức kiểm thử", "", "| Mức | Tổng | Tự động | Một phần | Chờ viết | Thủ công |", "|---|---|---|---|---|---|"]
    for lv in LEVELS:
        c = Counter(r["status"] for r in rows if r["level"] == lv)
        if sum(c.values()):
            lines.append(f"| {lv} | {sum(c.values())} | {c['automated']} | {c['partial']} | {c['planned']} | {c['manual']} |")
    lines += ["", "## P0 chưa có test tự động (ưu tiên viết khi tính năng sẵn sàng)", "", "| Test case | Chặn bởi | Người phụ trách |", "|---|---|---|"]
    for r in sorted((x for x in rows if x["priority"] == "P0" and x["status"] == "planned"), key=lambda x: x["id"]):
        lines.append(f"| {r['id']} | {r['gap']} | {r['owner']} |")
    lines += ["", "## Ma trận yêu cầu → test case", "", "| Yêu cầu | Test case | Trạng thái |", "|---|---|---|"]
    for req in sorted(set(ac_to_uc) | nfr_ids):
        for r in by_req.get(req, []):
            lines.append(f"| {req} | {r['id']} ({r['level']}, {r['priority']}) | {r['status']} |")
    with open(os.path.join(TESTING, "traceability.md"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")

    from xlsx_writer import write_cases
    write_cases(os.path.join(DIST, "Test_Cases.xlsx"), rows, by_req)
    import plan_docx
    plan_docx.write(rows, stats, os.path.join(DIST, "SHP_Master_Test_Plan_v0.9.docx"))


def main() -> int:
    m, rows, ac_to_uc, nfr_ids, stats, errors = analyse()
    if "--update-baseline" in sys.argv:
        with open(BASELINE, "w", encoding="utf-8", newline="\n") as fh:
            json.dump({k: stats[k] for k in ("automated_or_partial", "p0_automated_or_partial", "automated")}, fh, indent=2)
            fh.write("\n")
        print("baseline updated:", stats)
        return 0
    if errors:
        print("Traceability errors:")
        for e in errors:
            print("  -", e)
        return 1
    print(f"OK: {stats['total']} test cases; automated/partial {stats['automated_or_partial']} "
          f"(P0 {stats['p0_automated_or_partial']}/{stats['p0_total']}); fully automated {stats['automated']}")
    if "--check" not in sys.argv:
        write_reports(m, rows, ac_to_uc, nfr_ids, stats)
        print("wrote docs/06-testing/traceability.md and dist/Test_Cases.xlsx and the Master Test Plan")
    return 0


if __name__ == "__main__":
    sys.exit(main())
