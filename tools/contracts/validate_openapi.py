#!/usr/bin/env python3
"""Kiểm contracts/openapi: hợp lệ OpenAPI, đủ endpoint như tài liệu, mọi endpoint ngoài /internal có role khớp ma trận hoặc ghi chú.
Dùng: python3 -I tools/contracts/validate_openapi.py"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml
from openapi_spec_validator import validate

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_openapi  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    errs: list[str] = []
    expected = gen_openapi.parse()
    seen = 0
    for service, ops in expected.items():
        f = ROOT / "contracts/openapi" / f"{service}.yaml"
        if not f.exists():
            errs.append(f"{service}: thiếu {f.name}")
            continue
        spec = yaml.safe_load(f.read_text(encoding="utf-8"))
        try:
            validate(spec)
        except Exception as e:  # noqa: BLE001
            errs.append(f"{service}: OpenAPI không hợp lệ: {str(e)[:200]}")
            continue
        have = {(op["operationId"]) for p in spec["paths"].values() for op in p.values()}
        want = {o["id"] for o in ops}
        errs += [f"{service}: thiếu {i}" for i in sorted(want - have)] + [f"{service}: thừa {i}" for i in sorted(have - want)]
        seen += len(have)
        for path, item in spec["paths"].items():
            fhir = path.startswith("/fhir/")
            want_ref = "#/components/schemas/OperationOutcome" if fhir else "#/components/schemas/Problem"
            for method, op in item.items():
                for code, resp in (op.get("responses") or {}).items():
                    if not str(code).startswith(("4", "5")) or code == "default":
                        continue
                    content = (resp.get("content") or {})
                    schemas = [c.get("schema", {}).get("$ref") for c in content.values()]
                    if want_ref not in schemas:
                        errs.append(f"{service}: {method.upper()} {path} {code} thiếu schema {want_ref.split('/')[-1]}")

            for method, op in item.items():
                if not path.startswith("/internal/") and not op.get("x-roles") and "x-permission" in op:
                    perm = op["x-permission"]
                    if not perm:
                        errs.append(f"{service} {op['operationId']}: thiếu quyền")
    for e in errs:
        print("ERROR", e)
    if errs:
        return 1
    print(f"OK: {seen} operation, {len(expected)} service khớp tài liệu thiết kế")
    return 0


if __name__ == "__main__":
    sys.exit(main())
