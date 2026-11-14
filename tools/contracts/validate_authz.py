#!/usr/bin/env python3
"""Check contracts/authz/matrix.yaml: structure, security invariants, coverage. Usage: python3 -I tools/contracts/validate_authz.py"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]


def validate(doc: dict) -> list[str]:
    errs: list[str] = []
    roles, preds, purposes = set(doc["roles"]), set(doc["predicates"]), set(doc["purposes"])
    resources = doc["resources"]
    ids: set[str] = set()
    covered: set[tuple[str, str]] = set()
    deny = {(d["resource"], d["action"]) for d in doc.get("denyAll", [])}

    for r in doc["rules"]:
        rid = r.get("id", "?")
        if rid in ids:
            errs.append(f"{rid}: duplicate id")
        ids.add(rid)
        if r["resource"] not in resources:
            errs.append(f"{rid}: unknown resource {r['resource']}")
            continue
        if r["action"] not in resources[r["resource"]]["actions"]:
            errs.append(f"{rid}: action {r['action']} not declared for {r['resource']}")
        if r["role"] not in roles:
            errs.append(f"{rid}: unknown role {r['role']}")
        for p in r.get("when", []):
            if p not in preds:
                errs.append(f"{rid}: unknown predicate {p}")
        if "purpose" in r and r["purpose"] not in purposes:
            errs.append(f"{rid}: unknown purpose {r['purpose']}")
        if (r["resource"], r["action"]) in deny:
            errs.append(f"{rid}: violates denyAll")
        covered.add((r["resource"], r["action"]))
        clinical = resources[r["resource"]]["clinical"]
        if clinical and r["role"] in ("admin", "dpo"):
            errs.append(f"{rid}: admin/dpo may not touch clinical data")
        if r["role"] == "dpo" and r["resource"] != "Audit":
            errs.append(f"{rid}: dpo may only have rules for Audit")
        if "breakglass" in r.get("when", []) and r["role"] != "doctor":
            errs.append(f"{rid}: break-glass is only for doctor")
        if "own" in r.get("when", []) and r["role"] != "patient":
            errs.append(f"{rid}: the own predicate is only for patient")

    for res, spec in resources.items():
        for a in spec["actions"]:
            if (res, a) not in covered and (res, a) not in deny:
                errs.append(f"({res},{a}): no rule and not in denyAll")
    return errs


def main() -> int:
    doc = yaml.safe_load((ROOT / "contracts/authz/matrix.yaml").read_text(encoding="utf-8"))
    errs = validate(doc)
    for e in errs:
        print("ERROR", e)
    if errs:
        return 1
    print(f"OK: {len(doc['rules'])} rules, {len(doc['resources'])} resources, {len(doc['denyAll'])} denyAll")
    return 0


if __name__ == "__main__":
    sys.exit(main())
