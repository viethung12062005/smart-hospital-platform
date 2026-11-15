import unittest
from pathlib import Path
from typing import Any

import yaml

from tools.synthetic.generators.appointments import generate_appointments
from tools.synthetic.generators.clinical import generate_clinical_resources
from tools.synthetic.generators.documents import generate_documents
from tools.synthetic.generators.patients import generate_patients
from tools.synthetic.generators.practitioners import generate_practitioners
from tools.synthetic.generators.schedules import generate_schedules

SEED = 42
SCALE = "small"


def collect_references(value: Any) -> set[str]:
    """Collect all FHIR Reference.reference values recursively."""
    references: set[str] = set()

    if isinstance(value, dict):
        for key, item in value.items():
            if key == "reference" and isinstance(item, str):
                references.add(item)
            else:
                references.update(collect_references(item))

    elif isinstance(value, list):
        for item in value:
            references.update(collect_references(item))

    return references


def collect_codings(value: Any) -> list[tuple[str, str]]:
    """Collect all (system, code) pairs from FHIR Coding objects."""
    codings: list[tuple[str, str]] = []

    if isinstance(value, dict):
        if isinstance(value.get("coding"), list):
            for coding in value["coding"]:
                if isinstance(coding, dict):
                    system = coding.get("system")
                    code = coding.get("code")

                    if isinstance(system, str) and isinstance(code, str):
                        codings.append((system, code))

        for item in value.values():
            codings.extend(collect_codings(item))

    elif isinstance(value, list):
        for item in value:
            codings.extend(collect_codings(item))

    return codings


class TestDAT02Generators(unittest.TestCase):
    # Declare class attributes so Mypy can recognize them.
    patients: list[dict[str, Any]]
    practitioners: list[dict[str, Any]]
    roles: list[dict[str, Any]]
    schedules: list[dict[str, Any]]
    slots: list[dict[str, Any]]
    appointments: list[dict[str, Any]]
    encounters: list[dict[str, Any]]
    conditions: list[dict[str, Any]]
    observations: list[dict[str, Any]]
    document_references: list[dict[str, Any]]
    document_files: dict[str, str]

    @classmethod
    def setUpClass(cls) -> None:
        cls.patients = generate_patients(scale=SCALE, seed=SEED)

        (
            cls.practitioners,
            cls.roles,
        ) = generate_practitioners(scale=SCALE, seed=SEED)

        (
            cls.schedules,
            cls.slots,
        ) = generate_schedules(
            scale=SCALE,
            seed=SEED,
            practitioners=cls.practitioners,
            practitioner_roles=cls.roles,
        )

        cls.appointments = generate_appointments(
            scale=SCALE,
            seed=SEED,
            patients=cls.patients,
            practitioners=cls.practitioners,
            practitioner_roles=cls.roles,
            schedules=cls.schedules,
            slots=cls.slots,
        )

        (
            cls.encounters,
            cls.conditions,
            cls.observations,
        ) = generate_clinical_resources(
            seed=SEED,
            appointments=cls.appointments,
        )

        (
            cls.document_references,
            cls.document_files,
        ) = generate_documents(
            scale=SCALE,
            seed=SEED,
            patients=cls.patients,
            practitioners=cls.practitioners,
            encounters=cls.encounters,
        )

    def test_schedule_references_existing_practitioners(self) -> None:
        practitioner_refs = {f"Practitioner/{p['id']}" for p in self.practitioners}

        for schedule in self.schedules:
            references = collect_references(schedule)
            self.assertTrue(
                references.intersection(practitioner_refs),
                f"Schedule/{schedule['id']} thiếu Practitioner reference",
            )

    def test_slot_references_existing_schedules(self) -> None:
        schedule_refs = {f"Schedule/{s['id']}" for s in self.schedules}

        for slot in self.slots:
            references = collect_references(slot)
            self.assertTrue(
                references.intersection(schedule_refs),
                f"Slot/{slot['id']} thiếu Schedule reference",
            )

    def test_appointment_references_existing_resources(self) -> None:
        patient_refs = {f"Patient/{p['id']}" for p in self.patients}
        practitioner_refs = {f"Practitioner/{p['id']}" for p in self.practitioners}
        slot_refs = {f"Slot/{s['id']}" for s in self.slots}

        for appointment in self.appointments:
            references = collect_references(appointment)

            self.assertTrue(references.intersection(patient_refs))
            self.assertTrue(references.intersection(practitioner_refs))
            self.assertTrue(references.intersection(slot_refs))

    def test_encounters_only_reference_fulfilled_appointments(self) -> None:
        fulfilled_refs = {
            f"Appointment/{a['id']}"
            for a in self.appointments
            if a["status"] == "fulfilled"
        }

        self.assertEqual(len(self.encounters), len(fulfilled_refs))

        for encounter in self.encounters:
            references = collect_references(encounter)
            self.assertTrue(
                references.intersection(fulfilled_refs),
                f"Encounter/{encounter['id']} không tham chiếu Appointment fulfilled",
            )

    def test_conditions_reference_existing_patients_and_encounters(self) -> None:
        patient_refs = {f"Patient/{p['id']}" for p in self.patients}
        encounter_refs = {f"Encounter/{e['id']}" for e in self.encounters}

        for condition in self.conditions:
            references = collect_references(condition)

            self.assertTrue(references.intersection(patient_refs))
            self.assertTrue(references.intersection(encounter_refs))

    def test_observations_reference_existing_patients_and_encounters(self) -> None:
        patient_refs = {f"Patient/{p['id']}" for p in self.patients}
        encounter_refs = {f"Encounter/{e['id']}" for e in self.encounters}

        for observation in self.observations:
            references = collect_references(observation)

            self.assertTrue(references.intersection(patient_refs))
            self.assertTrue(references.intersection(encounter_refs))

    def test_document_references_have_valid_references(self) -> None:
        patient_refs = {f"Patient/{p['id']}" for p in self.patients}
        practitioner_refs = {f"Practitioner/{p['id']}" for p in self.practitioners}
        encounter_refs = {f"Encounter/{e['id']}" for e in self.encounters}

        for document in self.document_references:
            references = collect_references(document)

            self.assertTrue(references.intersection(patient_refs))
            self.assertTrue(references.intersection(practitioner_refs))
            self.assertTrue(references.intersection(encounter_refs))

    def test_document_files_have_synthetic_watermark(self) -> None:
        self.assertEqual(len(self.document_files), 100)

        for relative_path, content in self.document_files.items():
            self.assertTrue(relative_path.startswith("documents/"))
            self.assertTrue(relative_path.endswith(".svg"))
            self.assertIn("DỮ LIỆU GIẢ", content)
            self.assertIn("KHÔNG PHẢI HỒ SƠ Y TẾ THẬT", content)

    def test_document_attachment_paths_and_sizes(self) -> None:
        files = self.document_files

        for document in self.document_references:
            attachment = document["content"][0]["attachment"]

            relative_path = attachment["url"]
            self.assertIn(relative_path, files)

            expected_size = len(files[relative_path].encode("utf-8"))

            self.assertEqual(attachment["size"], expected_size)

    def test_dat02_ids_are_unique(self) -> None:
        resource_groups = [
            self.schedules,
            self.slots,
            self.appointments,
            self.encounters,
            self.conditions,
            self.observations,
            self.document_references,
        ]

        for resources in resource_groups:
            ids = [resource["id"] for resource in resources]
            self.assertEqual(len(ids), len(set(ids)))

    def test_generated_codes_belong_to_terminology_contract(self) -> None:
        contract_path = (
            Path(__file__).resolve().parents[2]
            / "contracts"
            / "terminology"
            / "code-systems.yaml"
        )

        with contract_path.open(encoding="utf-8") as file:
            contract = yaml.safe_load(file)

        code_systems: dict[str, set[str]] = {}

        for definition in contract["codeSystems"].values():
            system_url = definition["url"]
            codes = set(definition["concepts"])

            code_systems.setdefault(system_url, set()).update(codes)

        resource_groups = [
            self.practitioners,
            self.roles,
            self.schedules,
            self.slots,
            self.appointments,
            self.encounters,
            self.conditions,
            self.observations,
            self.document_references,
        ]

        for resources in resource_groups:
            for resource in resources:
                for system, code in collect_codings(resource):
                    if system in code_systems:
                        self.assertIn(
                            code,
                            code_systems[system],
                            f"Mã {code!r} không thuộc CodeSystem {system}",
                        )

    def test_terminology_contract_has_expected_code_systems(self) -> None:
        contract_path = (
            Path(__file__).resolve().parents[2]
            / "contracts"
            / "terminology"
            / "code-systems.yaml"
        )

        with contract_path.open(encoding="utf-8") as file:
            contract = yaml.safe_load(file)

        expected_systems = {
            "specialty",
            "cancel-reason",
            "booking-channel",
            "practitioner-role",
            "loinc-vital-signs",
            "loinc-document-types",
            "icd10-subset",
        }

        self.assertEqual(
            set(contract["codeSystems"]),
            expected_systems,
        )


if __name__ == "__main__":
    unittest.main()
