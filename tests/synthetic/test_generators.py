"""Kiểm bộ sinh dữ liệu giả DAT-01 theo docs/00-synthetic-data.md (ARC-11), nhất là các kiểm tra tự động ở §7."""

import re
import tempfile
import unittest
import uuid
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any

import yaml

from tools.synthetic.generate import generate
from tools.synthetic.generators.ids import UUID7Generator
from tools.synthetic.generators.names import GIVEN_NAMES, MIDDLE_NAMES
from tools.synthetic.generators.patients import (
    PATIENT_COUNTS,
    PHONE_CAPACITY,
    REFERENCE_DATE,
    fake_phone,
    generate_patients,
)
from tools.synthetic.generators.practitioners import (
    PRACTITIONER_COUNTS,
    ROLE_SYSTEM,
    SPECIALTIES,
    SPECIALTY_SYSTEM,
    generate_practitioners,
)

ROOT = Path(__file__).resolve().parents[2]
TERMINOLOGY = yaml.safe_load(
    (ROOT / "contracts/terminology/code-systems.yaml").read_text(encoding="utf-8")
)["codeSystems"]
FAKE_PHONE = re.compile(r"\+84900\d{6}")


def _age(birth: str) -> int:
    b = date.fromisoformat(birth)
    return (
        REFERENCE_DATE.year
        - b.year
        - ((REFERENCE_DATE.month, REFERENCE_DATE.day) < (b.month, b.day))
    )


def _gender_matches_name(gender: str, given: list[str]) -> bool:
    middle, name = given
    return middle in MIDDLE_NAMES[gender] and name in GIVEN_NAMES[gender]


class TestUUID7Generator(unittest.TestCase):
    def test_uuid7_version(self) -> None:
        generator = UUID7Generator(seed=42)
        for _ in range(100):
            self.assertEqual(generator.next().version, 7)

    def test_same_seed_same_ids(self) -> None:
        first, second = UUID7Generator(seed=42), UUID7Generator(seed=42)
        self.assertEqual(
            [first.next() for _ in range(10)], [second.next() for _ in range(10)]
        )

    def test_different_seed_different_ids(self) -> None:
        self.assertNotEqual(
            UUID7Generator(seed=42).next(), UUID7Generator(seed=99).next()
        )


class TestPatientGenerator(unittest.TestCase):
    medium: list[dict[str, Any]]

    @classmethod
    def setUpClass(cls) -> None:
        cls.medium = generate_patients(scale="medium", seed=42)

    def test_scale_counts(self) -> None:
        self.assertEqual(
            PATIENT_COUNTS, {"small": 200, "medium": 20_000, "large": 500_000}
        )
        self.assertEqual(len(generate_patients(scale="small", seed=42)), 200)
        self.assertEqual(len(self.medium), 20_000)

    def test_ids_are_unique_uuid7(self) -> None:
        ids = [p["id"] for p in self.medium]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(uuid.UUID(i).version == 7 for i in ids))

    def test_mrn_format_and_unique(self) -> None:
        mrn = [p["identifier"][0]["value"] for p in self.medium]
        self.assertTrue(all(re.fullmatch(r"BN-\d{4}-\d{6}", m) for m in mrn))
        self.assertEqual(len(mrn), len(set(mrn)))

    def test_phones_are_fake_and_unique(self) -> None:
        # §7: chỉ dải giả; 01 §4.1: SĐT dùng để chống trùng hồ sơ nên không được lặp
        phones = [p["telecom"][0]["value"] for p in self.medium]
        self.assertTrue(all(FAKE_PHONE.fullmatch(x) for x in phones))
        self.assertEqual(len(phones), len(set(phones)))

    def test_phone_range_covers_large_without_repeats(self) -> None:
        large = PATIENT_COUNTS["large"]
        self.assertGreaterEqual(PHONE_CAPACITY, large)
        self.assertEqual(len({fake_phone(i) for i in range(1, large + 1)}), large)
        # 100.000 số đầu nằm trong dải gốc 09000xxxxx của ARC-11
        self.assertEqual(fake_phone(1), "+84900000000")
        self.assertTrue(fake_phone(100_000).startswith("+849000"))
        with self.assertRaises(ValueError):
            fake_phone(PHONE_CAPACITY + 1)

    def test_emails_use_test_domain_and_are_unique(self) -> None:
        emails = [p["telecom"][1]["value"] for p in self.medium]
        self.assertTrue(all(e.endswith("@example.test") for e in emails))
        self.assertEqual(len(emails), len(set(emails)))

    def test_names_follow_gender(self) -> None:
        # ARC-11 §3: tên theo giới; không có nam tên đệm "Thị" hay tên nữ
        for p in self.medium:
            self.assertTrue(
                _gender_matches_name(p["gender"], p["name"][0]["given"]),
                p["name"][0]["text"],
            )

    def test_gender_ratio_is_about_49_51(self) -> None:
        male = Counter(p["gender"] for p in self.medium)["male"] / len(self.medium)
        self.assertAlmostEqual(male, 0.49, delta=0.02)

    def test_birth_dates_are_valid_and_follow_age_bands(self) -> None:
        ages = [_age(p["birthDate"]) for p in self.medium]
        self.assertTrue(all(0 <= a <= 120 for a in ages))
        bands = [(0, 14, 0.18), (15, 44, 0.35), (45, 64, 0.29), (65, 120, 0.18)]
        for lo, hi, share in bands:
            actual = sum(lo <= a <= hi for a in ages) / len(ages)
            self.assertAlmostEqual(actual, share, delta=0.02, msg=f"{lo}-{hi}")

    def test_fake_markers(self) -> None:
        small = generate_patients(scale="small", seed=42)
        self.assertTrue(all(p["name"][0]["text"].endswith(" (test)") for p in small))
        self.assertTrue(all("(giả)" in p["address"][0]["text"] for p in self.medium))

    def test_same_seed_same_patients(self) -> None:
        self.assertEqual(
            generate_patients(scale="small", seed=42),
            generate_patients(scale="small", seed=42),
        )

    def test_different_seed_different_patients(self) -> None:
        self.assertNotEqual(
            generate_patients(scale="small", seed=42),
            generate_patients(scale="small", seed=99),
        )


class TestPractitionerGenerator(unittest.TestCase):
    def test_scale_counts(self) -> None:
        self.assertEqual(
            PRACTITIONER_COUNTS, {"small": 12, "medium": 120, "large": 600}
        )
        practitioners, roles = generate_practitioners(scale="small", seed=42)
        self.assertEqual((len(practitioners), len(roles)), (12, 12))

    def test_ids_unique_across_practitioners_and_roles(self) -> None:
        practitioners, roles = generate_practitioners(scale="medium", seed=42)
        patient_ids = {p["id"] for p in generate_patients(scale="small", seed=42)}
        ids = [p["id"] for p in practitioners] + [r["id"] for r in roles]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertFalse(patient_ids & set(ids))

    def test_roles_reference_existing_practitioners(self) -> None:
        practitioners, roles = generate_practitioners(scale="medium", seed=42)
        known = {f"Practitioner/{p['id']}" for p in practitioners}
        self.assertTrue(all(r["practitioner"]["reference"] in known for r in roles))

    def test_codes_come_from_contracts_terminology(self) -> None:
        # 00-synthetic-data §2: không tự bịa mã
        self.assertEqual(SPECIALTY_SYSTEM, TERMINOLOGY["specialty"]["url"])
        self.assertEqual(SPECIALTIES, TERMINOLOGY["specialty"]["concepts"])
        self.assertEqual(ROLE_SYSTEM, TERMINOLOGY["practitioner-role"]["url"])
        _, roles = generate_practitioners(scale="medium", seed=42)
        for role in roles:
            coding = role["code"][0]["coding"][0]
            self.assertEqual(coding["system"], ROLE_SYSTEM)
            self.assertIn(coding["code"], TERMINOLOGY["practitioner-role"]["concepts"])
            self.assertIn(
                role["specialty"][0]["coding"][0]["code"],
                TERMINOLOGY["specialty"]["concepts"],
            )

    def test_names_follow_gender_and_emails_are_test_domain(self) -> None:
        practitioners, _ = generate_practitioners(scale="medium", seed=42)
        for p in practitioners:
            middle, name = p["name"][0]["text"].split(" ")[1:]
            self.assertTrue(
                _gender_matches_name(p["gender"], [middle, name]), p["name"][0]["text"]
            )
            self.assertTrue(p["telecom"][0]["value"].endswith("@example.test"))

    def test_same_seed_same_practitioners(self) -> None:
        self.assertEqual(
            generate_practitioners(scale="small", seed=42),
            generate_practitioners(scale="small", seed=42),
        )

    def test_different_seed_different_practitioners(self) -> None:
        self.assertNotEqual(
            generate_practitioners(scale="small", seed=42),
            generate_practitioners(scale="small", seed=99),
        )


class TestReproducibleOutput(unittest.TestCase):
    def test_same_seed_same_files_and_manifest_hashes(self) -> None:
        # §7: cùng seed cho cùng hash đầu ra (so sánh từng byte, không chỉ so object)
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            generate(scale="small", seed=42, output_root=Path(a))
            generate(scale="small", seed=42, output_root=Path(b))
            files = sorted(p.name for p in (Path(a) / "small-seed-42").iterdir())
            self.assertEqual(
                files,
                [
                    "manifest.json",
                    "patients.json",
                    "practitioner_roles.json",
                    "practitioners.json",
                ],
            )
            for name in files:
                self.assertEqual(
                    (Path(a) / "small-seed-42" / name).read_bytes(),
                    (Path(b) / "small-seed-42" / name).read_bytes(),
                )


if __name__ == "__main__":
    unittest.main()
