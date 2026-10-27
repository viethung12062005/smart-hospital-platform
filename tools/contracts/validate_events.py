#!/usr/bin/env python3
"""Kiểm contracts/events: schema hợp lệ, ví dụ khớp schema, envelope mẫu hợp lệ, không có trường PII, khớp danh mục E1–E11.
Dùng: python3 -I tools/contracts/validate_events.py"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
EV = ROOT / "contracts" / "events"
PII = re.compile(r"(name|phone|email|address|birth|diagnos|note|content|telecom)", re.I)
ALLOWED_PII_LIKE = {"contentType", "uploadedBy", "specialty", "cancelReason", "reason", "userAgent", "resource", "modelName", "sourcePatientId", "targetPatientId"}


def prop_names(schema: dict) -> list[str]:
    out: list[str] = []
    for k, v in schema.get("properties", {}).items():
        out.append(k)
        if v.get("type") == "object":
            out += prop_names(v)
    return out


def main() -> int:
    errs: list[str] = []
    catalog = (ROOT / "docs/03-architecture/detailed-design/13-events.md").read_text(encoding="utf-8")
    declared = set(re.findall(r"`(shp\.[a-z]+\.[a-z-]+\.v1)`", catalog))
    envelope = json.loads((EV / "envelope.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(envelope)
    env_v = Draft202012Validator(envelope)
    found: set[str] = set()
    for sf in sorted(EV.glob("*/*.v1.json")):
        if sf.name.endswith(".example.json"):
            continue
        schema = json.loads(sf.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        title = schema["title"]
        found.add(title)
        example = json.loads(sf.with_name(sf.stem + ".example.json").read_text(encoding="utf-8"))
        v = Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER)
        errs += [f"{title}: ví dụ sai: {e.message}" for e in v.iter_errors(example)]
        for n in prop_names(schema):
            if PII.search(n) and n not in ALLOWED_PII_LIKE:
                errs.append(f"{title}: tên trường nghi PII: {n}")
        ctx, name = sf.parent.name, sf.stem.removesuffix(".v1")
        envl = {"specversion": "1.0", "id": "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f", "source": f"/services/{ctx}", "type": title,
                "subject": "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70", "time": "2026-11-20T02:15:00.123Z",
                "datacontenttype": "application/json", "dataschema": schema["$id"],
                "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "data": example}
        errs += [f"{title}: envelope sai: {e.message}" for e in env_v.iter_errors(envl)]
        if len(json.dumps(example)) > 64 * 1024:
            errs.append(f"{title}: vượt 64 KB")
    for t in sorted(declared - found):
        errs.append(f"{t}: có trong danh mục nhưng thiếu schema")
    for t in sorted(found - declared):
        errs.append(f"{t}: có schema nhưng không có trong danh mục")
    for e in errs:
        print("ERROR", e)
    if errs:
        return 1
    print(f"OK: {len(found)} event schema khớp danh mục, ví dụ và envelope hợp lệ, không có trường PII")
    return 0


if __name__ == "__main__":
    sys.exit(main())
