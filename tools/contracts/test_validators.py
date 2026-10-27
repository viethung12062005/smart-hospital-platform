"""Kiểm chính bộ kiểm tra: ma trận sai phải bị bắt. Chạy: python3 -I -m unittest discover -s tools/contracts -p 'test_*.py'"""
import copy
import sys
import unittest
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import validate_authz  # noqa: E402

DOC = yaml.safe_load((Path(__file__).resolve().parents[2] / "contracts/authz/matrix.yaml").read_text(encoding="utf-8"))


def mutated(rule: dict | None = None, drop: str | None = None) -> list[str]:
    d = copy.deepcopy(DOC)
    if drop:
        d["rules"] = [r for r in d["rules"] if r["id"] != drop]
    if rule:
        d["rules"].append(rule)
    return validate_authz.validate(d)


class AuthzMatrix(unittest.TestCase):
    def test_current_matrix_is_valid(self):
        self.assertEqual(validate_authz.validate(DOC), [])

    def test_admin_cannot_read_clinical_data(self):
        self.assertTrue(any("lâm sàng" in e for e in mutated({"id": "X", "resource": "Patient", "action": "read", "role": "admin", "when": []})))

    def test_dpo_limited_to_audit(self):
        self.assertTrue(any("dpo" in e for e in mutated({"id": "X", "resource": "Slot", "action": "read", "role": "dpo", "when": []})))

    def test_audit_delete_is_forbidden(self):
        self.assertTrue(any("denyAll" in e for e in mutated({"id": "X", "resource": "Audit", "action": "delete", "role": "dpo", "when": []})))

    def test_unknown_predicate_and_duplicate_id(self):
        errs = mutated({"id": "PAT-READ-OWN", "resource": "Patient", "action": "read", "role": "patient", "when": ["nope"]})
        self.assertTrue(any("predicate lạ" in e for e in errs) and any("id trùng" in e for e in errs))

    def test_missing_rule_for_declared_action_is_caught(self):
        self.assertTrue(any("(BreakGlass,start)" in e for e in mutated(drop="BG-START-DOC")))

    def test_breakglass_only_for_doctor(self):
        self.assertTrue(any("break-glass" in e for e in mutated({"id": "X", "resource": "Slot", "action": "read", "role": "nurse", "when": ["breakglass"]})))


if __name__ == "__main__":
    unittest.main()
