#!/usr/bin/env python3
"""Check contracts/terminology: code format, no duplicates, matches 01-domain-model.md §3 and event E8. Usage: python3 -I tools/contracts/validate_terminology.py"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
FMT = {"loinc-vital-signs": r"^\d{1,5}-\d$", "loinc-document-types": r"^\d{1,5}-\d$", "icd10-subset": r"^[A-Z]\d{2}(\.\d{1,2})?$",
       "specialty": r"^[a-z]+(-[a-z]+)*$", "cancel-reason": r"^[a-z]+(-[a-z]+)*$", "booking-channel": r"^[a-z]+$",
       "practitioner-role": r"^[a-z]+$"}


def main() -> int:
    errs: list[str] = []
    cs = yaml.safe_load((ROOT / "contracts/terminology/code-systems.yaml").read_text(encoding="utf-8"))["codeSystems"]
    doc = (ROOT / "docs/03-architecture/detailed-design/01-domain-model.md").read_text(encoding="utf-8")
    for name, spec in cs.items():
        for code, label in spec["concepts"].items():
            if not re.match(FMT[name], str(code)):
                errs.append(f"{name}: invalid code format: {code}")
            if not label:
                errs.append(f"{name}: {code} is missing a label")
            if name in ("specialty", "cancel-reason", "booking-channel", "practitioner-role", "loinc-vital-signs",
                        "loinc-document-types") and f"`{code}`" not in doc:
                errs.append(f"{name}: {code} is not in 01-domain-model.md §3")
    for code in re.findall(
        r"`(\d{2,5}-\d)` (?:heart rate|blood pressure|temperature|respiratory rate|SpO2|weight|height|BMI|"
        r"laboratory|diagnostic|prescription|referral|care encounter|administrative)",
        doc,
    ):
        if code not in {str(c) for s in ("loinc-vital-signs", "loinc-document-types") for c in cs[s]["concepts"]}:
            errs.append(f"LOINC code in the docs but missing from the catalog: {code}")
    raw = yaml.safe_load((ROOT / "contracts/terminology/code-systems.yaml").read_text(encoding="utf-8"))
    ext = raw.get("extensions", {})
    for key, spec in ext.items():
        if spec.get("url") != f"https://shp.example.org/fhir/StructureDefinition/{key}":
            errs.append(f"extension {key}: url must be https://shp.example.org/fhir/StructureDefinition/{key}")
        for field in ("context", "value"):
            if not spec.get(field):
                errs.append(f"extension {key}: missing {field}")
    documented = set(re.findall(r"`extension\[([a-z-]+)\]`", doc))
    for key in sorted(documented - set(ext)):
        errs.append(f"extension[{key}] is in 01-domain-model but missing from contracts/terminology")
    for key in sorted(set(ext) - documented):
        errs.append(f"extension {key} is in contracts/terminology but unused in 01-domain-model")
    # ICD-10: ARC-20 asks for a ~300-code subset; keep it from shrinking silently
    icd = cs["icd10-subset"]["concepts"]
    if len(icd) < 300:
        errs.append(f"icd10-subset has {len(icd)} codes, needs >= 300 (ARC-20)")
    # YAML silently overwrites duplicate keys: scan the raw lines of each block
    raw_lines = (ROOT / "contracts/terminology/code-systems.yaml").read_text(encoding="utf-8").splitlines()
    for block in ("specialty", "cancel-reason", "booking-channel", "practitioner-role", "icd10-subset"):
        seen: set[str] = set()
        inside = False
        for line in raw_lines:
            if line.strip() == f"{block}:":
                inside = True
                continue
            if not inside:
                continue
            if line and not line.startswith("    "):
                break  # a new code-system header (two spaces) or a top-level key ends this block
            if ":" in line:
                key = line.strip().split(":", 1)[0]
                if key in seen:
                    errs.append(f"{block}: duplicate code {key}")
                seen.add(key)
    ev = json.loads((ROOT / "contracts/events/document/document-uploaded.v1.example.json").read_text(encoding="utf-8"))
    if ev["type"] not in {str(c) for c in cs["loinc-document-types"]["concepts"]}:
        errs.append("E8 example uses a document type outside the catalog")
    for e in errs:
        print("ERROR", e)
    if errs:
        return 1
    print("OK: " + ", ".join(f"{n}={len(s['concepts'])}" for n, s in cs.items()) + f", extensions={len(ext)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
