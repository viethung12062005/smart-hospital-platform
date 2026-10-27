#!/usr/bin/env python3
"""Sinh khung OpenAPI 3.1 cho từng service từ các bảng endpoint trong detailed-design/08..12.
Dùng: python3 -I tools/contracts/gen_openapi.py   (ghi đè contracts/openapi/<service>.yaml)

Khung này có: đường dẫn, method, operationId (=ID trong tài liệu, ví dụ S11), tóm tắt, quyền (x-roles), use case, lỗi chuẩn.
KHÔNG có schema thân request/response: dev lấy từ tài liệu (mục tương ứng) và thêm vào file khi làm task endpoint;
validate_openapi.py chặn thiếu/thừa endpoint so với tài liệu. Endpoint /internal/* gắn x-internal: true (gateway không route).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs/03-architecture/detailed-design"
OUT = ROOT / "contracts" / "openapi"

# (file tài liệu, tiền tố ID) → service. Một file có thể chứa nhiều service (11: notification, audit, ml).
SOURCES = [
    ("08-api-scheduling.md", {"S": "scheduling"}),
    ("09-api-clinical.md", {"C": "clinical"}),
    ("10-api-document.md", {"D": "document"}),
    ("11-api-notification-audit-ml.md", {"N": "notification", "A": "audit", "M": "ml"}),
    ("12-api-ai.md", {"AI": "ai"}),
]
ROW = re.compile(r"^\| ([A-Z]+)(\d+) \| (.+?) \| (.+?) \|(?: (.+?) \|)?(?: (.+?) \|)?\s*$")
OP = re.compile(r"`([A-Z]+) ([^`]+)`")

ROLE = {"P": "patient", "D": "doctor", "N": "nurse", "A": "admin", "DPO": "dpo"}
# Bảng audit không có cột quyền; quyền nằm ở đầu mục §2 của 11-api-notification-audit-ml.md.
DEFAULT_PERM = {"audit": "DPO, A"}
STD_ERRORS = {"400": "Yêu cầu sai", "401": "Chưa xác thực", "403": "Không đủ quyền (có ghi audit)", "404": "Không tìm thấy",
              "409": "Xung đột (version/idempotency)", "422": "Dữ liệu không hợp lệ", "429": "Quá giới hạn tốc độ"}


def parse() -> dict[str, list[dict]]:
    by_service: dict[str, list[dict]] = {}
    for fname, prefixes in SOURCES:
        for line in (DOCS / fname).read_text(encoding="utf-8").splitlines():
            m = ROW.match(line)
            if not m or m.group(1) not in prefixes or not OP.search(m.group(3)):
                continue
            ops = OP.findall(m.group(3))
            for i, (method, path) in enumerate(ops):
                opid = f"{m.group(1)}{m.group(2)}" + (f"-{i + 1}" if len(ops) > 1 else "")
                by_service.setdefault(prefixes[m.group(1)], []).append({
                    "id": opid, "method": method.lower(), "path": path.strip(), "summary": m.group(4).strip(),
                    "perm": (m.group(5) or DEFAULT_PERM.get(prefixes[m.group(1)], "")).strip(), "doc": fname})
    return by_service


def roles_of(perm: str) -> list[str]:
    found = []
    for token in re.split(r"[;,]", perm):
        t = token.strip().split(" ")[0]
        if t in ROLE and ROLE[t] not in found:
            found.append(ROLE[t])
    return found


def build(service: str, ops: list[dict]) -> dict:
    paths: dict[str, dict] = {}
    for o in ops:
        internal = o["path"].startswith("/internal/")
        path = o["path"].split("?")[0]
        params = [{"name": n, "in": "path", "required": True, "schema": {"type": "string"}} for n in re.findall(r"\{([A-Za-z]+)\}", path)]
        fhir = path.startswith("/fhir/")
        err_ct = "application/fhir+json" if fhir else "application/problem+json"
        entry = {
            "operationId": o["id"], "summary": o["summary"], "tags": [service],
            "x-permission": o["perm"], "x-roles": roles_of(o["perm"]), "x-doc": f"detailed-design/{o['doc']}",
            "responses": {"200": {"description": "Thành công"}} | {
                code: {"description": d, "content": {err_ct: {}}} for code, d in STD_ERRORS.items()},
        }
        if params:
            entry["parameters"] = params
        if internal:
            entry["x-internal"] = True
            entry["security"] = [{"workloadIdentity": []}]
        paths.setdefault(path, {})[o["method"]] = entry
    return {
        "openapi": "3.1.0",
        "info": {"title": f"SHP {service} API", "version": "1.0.0",
                 "description": "Khung sinh từ tài liệu thiết kế; thêm schema thân request/response khi làm task endpoint."},
        "servers": [{"url": "https://api.{env}.shp.example.org", "variables": {"env": {"default": "uat"}}}],
        "security": [{"oidc": []}],
        "paths": paths,
        "components": {"securitySchemes": {
            "oidc": {"type": "openIdConnect", "openIdConnectUrl": "https://login.example.org/.well-known/openid-configuration"},
            "workloadIdentity": {"type": "http", "scheme": "bearer", "description": "Token workload identity của service gọi (nội bộ)"}}},
    }


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    total = 0
    for service, ops in parse().items():
        (OUT / f"{service}.yaml").write_text(
            "# SINH BỞI tools/contracts/gen_openapi.py từ docs/03-architecture/detailed-design/08..12. Thêm schema ở dưới các operation.\n"
            + yaml.safe_dump(build(service, ops), allow_unicode=True, sort_keys=False, width=140), encoding="utf-8")
        total += len(ops)
        print(f"{service}: {len(ops)} operation")
    print(f"tổng {total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
