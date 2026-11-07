import unittest

from tools.synthetic.generators.ids import UUID7Generator
from tools.synthetic.generators.patients import (
    PATIENT_COUNTS,
    generate_patients,
)
from tools.synthetic.generators.practitioners import (
    PRACTITIONER_COUNTS,
    generate_practitioners,
)


class TestUUID7Generator(unittest.TestCase):
    def test_uuid7_version(self) -> None:
        generator = UUID7Generator(seed=42)

        for _ in range(100):
            self.assertEqual(generator.next().version, 7)

    def test_same_seed_same_ids(self) -> None:
        first = UUID7Generator(seed=42)
        second = UUID7Generator(seed=42)

        ids_first = [str(first.next()) for _ in range(10)]
        ids_second = [str(second.next()) for _ in range(10)]

        self.assertEqual(ids_first, ids_second)

    def test_different_seed_different_ids(self) -> None:
        first = UUID7Generator(seed=42)
        second = UUID7Generator(seed=99)

        self.assertNotEqual(str(first.next()), str(second.next()))


class TestPatientGenerator(unittest.TestCase):
    def test_scale_counts(self) -> None:
        self.assertEqual(PATIENT_COUNTS["small"], 200)
        self.assertEqual(PATIENT_COUNTS["medium"], 20_000)
        self.assertEqual(PATIENT_COUNTS["large"], 500_000)

    def test_small_scale_count(self) -> None:
        patients = generate_patients(scale="small", seed=42)
        self.assertEqual(len(patients), 200)

    def test_patient_ids_are_unique(self) -> None:
        patients = generate_patients(scale="small", seed=42)
        ids = [patient["id"] for patient in patients]

        self.assertEqual(len(ids), len(set(ids)))

    def test_patient_ids_are_uuid7(self) -> None:
        patients = generate_patients(scale="small", seed=42)

        for patient in patients:
            import uuid

            self.assertEqual(uuid.UUID(patient["id"]).version, 7)

    def test_patient_emails_use_test_domain(self) -> None:
        patients = generate_patients(scale="small", seed=42)

        for patient in patients:
            email = patient["telecom"][1]["value"]
            self.assertTrue(email.endswith("@example.test"))

    def test_patient_phones_use_fake_range(self) -> None:
        patients = generate_patients(scale="small", seed=42)

        for patient in patients:
            phone = patient["telecom"][0]["value"]
            self.assertTrue(phone.startswith("+849000"))

    def test_same_seed_same_patients(self) -> None:
        first = generate_patients(scale="small", seed=42)
        second = generate_patients(scale="small", seed=42)

        self.assertEqual(first, second)

    def test_different_seed_different_patients(self) -> None:
        first = generate_patients(scale="small", seed=42)
        second = generate_patients(scale="small", seed=99)

        self.assertNotEqual(first, second)


class TestPractitionerGenerator(unittest.TestCase):
    def test_scale_counts(self) -> None:
        self.assertEqual(PRACTITIONER_COUNTS["small"], 12)
        self.assertEqual(PRACTITIONER_COUNTS["medium"], 120)
        self.assertEqual(PRACTITIONER_COUNTS["large"], 600)

    def test_small_scale_counts(self) -> None:
        practitioners, roles = generate_practitioners(
            scale="small",
            seed=42,
        )

        self.assertEqual(len(practitioners), 12)
        self.assertEqual(len(roles), 12)

    def test_practitioner_ids_are_unique(self) -> None:
        practitioners, _ = generate_practitioners(
            scale="small",
            seed=42,
        )

        ids = [item["id"] for item in practitioners]
        self.assertEqual(len(ids), len(set(ids)))

    def test_roles_reference_existing_practitioners(self) -> None:
        practitioners, roles = generate_practitioners(
            scale="small",
            seed=42,
        )

        practitioner_ids = {
            item["id"] for item in practitioners
        }

        for role in roles:
            reference = role["practitioner"]["reference"]
            practitioner_id = reference.split("/", maxsplit=1)[1]

            self.assertIn(practitioner_id, practitioner_ids)

    def test_role_codes_are_valid(self) -> None:
        _, roles = generate_practitioners(
            scale="small",
            seed=42,
        )

        for role in roles:
            code = role["code"][0]["coding"][0]["code"]
            self.assertIn(code, {"doctor", "nurse"})

    def test_same_seed_same_practitioners(self) -> None:
        first = generate_practitioners(scale="small", seed=42)
        second = generate_practitioners(scale="small", seed=42)

        self.assertEqual(first, second)

    def test_different_seed_different_practitioners(self) -> None:
        first = generate_practitioners(scale="small", seed=42)
        second = generate_practitioners(scale="small", seed=99)

        self.assertNotEqual(first, second)


if __name__ == "__main__":
    unittest.main()