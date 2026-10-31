"""Shared loading for the QA tools: SRS model, test case catalog, automation map."""
from __future__ import annotations

import glob
import os
import re
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
TESTING = os.path.join(ROOT, "docs", "06-testing")
CATALOG = os.path.join(TESTING, "catalog")
CONTENT = os.path.join(TESTING, "content")
DIST = os.path.join(TESTING, "dist")

sys.path.insert(0, os.path.join(ROOT, "tools", "srs"))

LEVELS = ["unit", "contract", "integration", "api", "e2e", "security", "performance", "resilience", "ai-eval", "monitoring", "ci", "review", "manual"]
PRIORITIES = ["P0", "P1", "P2"]
AUTOMATION = ["automated", "partial", "planned", "manual"]

# Requirements that touch patient data, identity, consent or patient safety are P0 (a failure blocks release).
P0_RE = re.compile(
    r"quyền|từ chối|audit|PII|họ tên|SĐT|định danh|đồng ý|rút đồng ý|khẩn cấp|break|mã độc|cách ly|nhiễm|token|MFA|khóa|"
    r"bất biến|lộ |404|người khác|bệnh nhân khác|prompt|injection|consent|idempotency|trùng|xác nhận rõ|không bao giờ|"
    r"không có quyền|mã hóa|secret|an toàn",
    re.I,
)


def load_srs():
    import build as srs_build  # tools/srs/build.py
    return srs_build.Model()


def load_yaml(path):
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


def load_catalog():
    """All test cases from catalog/*.yaml (except automation.yaml), keyed by id."""
    cases = {}
    for path in sorted(glob.glob(os.path.join(CATALOG, "*.yaml"))):
        if os.path.basename(path) == "automation.yaml":
            continue
        for tc in load_yaml(path).get("testcases", []):
            cases[tc["id"]] = {**tc, "_file": os.path.relpath(path, ROOT)}
    return cases


def load_automation():
    return load_yaml(os.path.join(CATALOG, "automation.yaml")).get("automation", {})
