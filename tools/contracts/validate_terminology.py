#!/usr/bin/env python3
"""Kiểm contracts/terminology: định dạng mã, không trùng, khớp 01-domain-model.md §3 và event E8. Dùng: python3 -I tools/contracts/validate_terminology.py"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
FMT = {"loinc-vital-signs": r"^\d{1,5}-\d$", "loinc-document-types": r"^\d{1,5}-\d$", "icd10-subset": r"^[A-Z]\d{2}(\.\d{1,2})?$",
       "specialty": r"^[a-z]+(-[a-z]+)*$", "cancel-reason": r"^[a-z]+(-[a-z]+)*$", "booking-channel": r"^[a-z]+$"}


def main() -> int:
    errs: list[str] = []
    cs = yaml.safe_load((ROOT / "contracts/terminology/code-systems.yaml").read_text(encoding="utf-8"))["codeSystems"]
    doc = (ROOT / "docs/03-architecture/detailed-design/01-domain-model.md").read_text(encoding="utf-8")
    for name, spec in cs.items():
        for code, label in spec["concepts"].items():
            if not re.match(FMT[name], str(code)):
                errs.append(f"{name}: mã sai định dạng: {code}")
            if not label:
                errs.append(f"{name}: {code} thiếu nhãn")
            if name in ("specialty", "cancel-reason", "booking-channel", "loinc-vital-signs", "loinc-document-types") and f"`{code}`" not in doc:
                errs.append(f"{name}: {code} không có trong 01-domain-model.md §3")
    for code in re.findall(r"`(\d{2,5}-\d)` (?:kết quả|đơn|giấy|tóm|mạch|huyết|nhiệt|nhịp|SpO2|cân|chiều|BMI)", doc):
        if code not in {str(c) for s in ("loinc-vital-signs", "loinc-document-types") for c in cs[s]["concepts"]}:
            errs.append(f"mã LOINC trong tài liệu nhưng thiếu ở danh mục: {code}")
    ev = json.loads((ROOT / "contracts/events/document/document-uploaded.v1.example.json").read_text(encoding="utf-8"))
    if ev["type"] not in {str(c) for c in cs["loinc-document-types"]["concepts"]}:
        errs.append("ví dụ E8 dùng loại tài liệu ngoài danh mục")
    for e in errs:
        print("ERROR", e)
    if errs:
        return 1
    print("OK: " + ", ".join(f"{n}={len(s['concepts'])}" for n, s in cs.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
