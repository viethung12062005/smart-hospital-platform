#!/usr/bin/env python3
"""Generate an OpenAPI 3.1 skeleton for each service from the endpoint tables in detailed-design/08..12.
Usage: python3 -I tools/contracts/gen_openapi.py   (overwrites contracts/openapi/<service>.yaml)

The skeleton has: paths, methods, operationId (= ID in the docs, e.g. S11), summary, permissions (x-roles), use case, standard errors.
It has NO request/response body schemas: devs take them from the docs (relevant section) and add them to the file while implementing the endpoint;
validate_openapi.py rejects missing/extra endpoints relative to the docs. /internal/* endpoints get x-internal: true (the gateway does not route them).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs/03-architecture/detailed-design"
OUT = ROOT / "contracts" / "openapi"

# (doc file, ID prefix) → service. One file may contain several services (11: notification, audit, ml).
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
# The audit table has no permission column; permissions are at the start of section §2 of 11-api-notification-audit-ml.md.
DEFAULT_PERM = {"audit": "DPO, A"}
STD_ERRORS = {"400": "Bad request", "401": "Not authenticated", "403": "Insufficient permission (audited)", "404": "Not found",
              "409": "Conflict (version/idempotency)", "422": "Invalid data", "429": "Rate limit exceeded"}


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


SCHEMAS = {
    # FHIR R4 OperationOutcome: error shape of every /fhir/** endpoint (07 §5)
    "OperationOutcome": {
        "type": "object",
        "required": ["resourceType", "issue"],
        "properties": {
            "resourceType": {"const": "OperationOutcome"},
            "issue": {
                "type": "array",
                "minItems": 1,
                "items": {
                    "type": "object",
                    "required": ["severity", "code"],
                    "properties": {
                        "severity": {"enum": ["fatal", "error", "warning", "information"]},
                        "code": {"type": "string", "description": "Code in 07 §5, e.g. validation-failed"},
                        "diagnostics": {"type": "string", "description": "vi/en translation, contains no submitted value"},
                        "details": {
                            "type": "object",
                            "properties": {
                                "text": {"type": "string"},
                                "coding": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "system": {"type": "string"},
                                            "code": {"type": "string"},
                                            "display": {"type": "string"},
                                        },
                                    },
                                },
                            },
                        },
                    },
                },
            },
        },
    },
    # RFC 9457 Problem Details: error shape of every /api/** endpoint
    "Problem": {
        "type": "object",
        "required": ["type", "title", "status", "code"],
        "properties": {
            "type": {"type": "string", "format": "uri"},
            "title": {"type": "string", "description": "vi/en translation of the error code"},
            "status": {"type": "integer", "minimum": 400, "maximum": 599},
            "code": {"type": "string", "description": "Code in the ErrorCatalog (07 §5)"},
            "detail": {"type": "string"},
            "traceId": {"type": "string", "description": "Trace id for log correlation"},
        },
    },
}


def build(service: str, ops: list[dict]) -> dict:
    paths: dict[str, dict] = {}
    for o in ops:
        internal = o["path"].startswith("/internal/")
        path = o["path"].split("?")[0]
        params = [{"name": n, "in": "path", "required": True, "schema": {"type": "string"}} for n in re.findall(r"\{([A-Za-z]+)\}", path)]
        fhir = path.startswith("/fhir/")
        err_ct = "application/fhir+json" if fhir else "application/problem+json"
        err_schema = "#/components/schemas/OperationOutcome" if fhir else "#/components/schemas/Problem"
        entry = {
            "operationId": o["id"], "summary": o["summary"], "tags": [service],
            "x-permission": o["perm"], "x-roles": roles_of(o["perm"]), "x-doc": f"detailed-design/{o['doc']}",
            "responses": {"200": {"description": "Success"}} | {
                code: {"description": d, "content": {err_ct: {"schema": {"$ref": err_schema}}}}
                for code, d in STD_ERRORS.items()},
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
                 "description": "Skeleton generated from the design docs; add request/response body schemas when implementing the endpoint task."},
        "servers": [{"url": "https://api.{env}.shp.example.org", "variables": {"env": {"default": "uat"}}}],
        "security": [{"oidc": []}],
        "paths": paths,
        "components": {"schemas": SCHEMAS, "securitySchemes": {
            "oidc": {"type": "openIdConnect", "openIdConnectUrl": "https://login.example.org/.well-known/openid-configuration"},
            "workloadIdentity": {"type": "http", "scheme": "bearer", "description": "Workload identity token of the calling service (internal)"}}},
    }


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    total = 0
    for service, ops in parse().items():
        (OUT / f"{service}.yaml").write_text(
            "# GENERATED BY tools/contracts/gen_openapi.py from docs/03-architecture/detailed-design/08..12. Add schemas under the operations.\n"
            + yaml.safe_dump(build(service, ops), allow_unicode=True, sort_keys=False, width=140), encoding="utf-8")
        total += len(ops)
        print(f"{service}: {len(ops)} operation")
    print(f"total {total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
