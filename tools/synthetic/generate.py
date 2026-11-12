"""Command-line interface for generating synthetic FHIR data."""

import argparse
import hashlib
import json
from pathlib import Path
from typing import TypedDict

from tools.synthetic.generators.appointments import generate_appointments
from tools.synthetic.generators.clinical import generate_clinical_resources
from tools.synthetic.generators.documents import generate_documents
from tools.synthetic.generators.patients import (
    PATIENT_COUNTS,
    generate_patients,
)
from tools.synthetic.generators.practitioners import (
    generate_practitioners,
)

# DAT-02: Additional synthetic resource generators
from tools.synthetic.generators.schedules import generate_schedules


class Manifest(TypedDict):
    generator: str
    scale: str
    seed: int
    counts: dict[str, int]
    sha256: dict[str, str]


DEFAULT_OUTPUT = Path("dev/.local/synthetic")


def write_json(path: Path, data: object) -> str:
    """Write deterministic UTF-8 JSON and return its SHA-256."""
    content = (
        json.dumps(
            data,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n"
    )

    encoded = content.encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded)

    return hashlib.sha256(encoded).hexdigest()


def write_text(path: Path, content: str) -> str:
    """Write deterministic UTF-8 text and return its SHA-256."""
    encoded = content.encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded)

    return hashlib.sha256(encoded).hexdigest()


def generate(scale: str, seed: int, output_root: Path) -> None:
    """Generate all DAT-01 and DAT-02 resources."""

    if scale not in PATIENT_COUNTS:
        raise ValueError(f"Scale không hợp lệ: {scale}")

    output_dir = output_root / f"{scale}-seed-{seed}"
    output_dir.mkdir(parents=True, exist_ok=True)

    # =========================================================
    # DAT-01: Patients, Practitioners and PractitionerRoles
    # =========================================================

    patients = generate_patients(scale=scale, seed=seed)

    practitioners, roles = generate_practitioners(
        scale=scale,
        seed=seed,
    )

    # =========================================================
    # DAT-02: Schedules and Slots
    # =========================================================

    schedules, slots = generate_schedules(
        scale=scale,
        seed=seed,
        practitioners=practitioners,
        practitioner_roles=roles,
    )

    # =========================================================
    # DAT-02: Appointments
    # =========================================================

    appointments = generate_appointments(
        scale=scale,
        seed=seed,
        patients=patients,
        practitioners=practitioners,
        practitioner_roles=roles,
        schedules=schedules,
        slots=slots,
    )

    # Update Slot status based on Appointment status.
    # A fulfilled or no-show appointment occupies its slot.
    appointment_by_slot = {}

    for appointment in appointments:
        for slot_reference in appointment["slot"]:
            appointment_by_slot[slot_reference["reference"]] = appointment["status"]

    for slot in slots:
        slot_reference = f"Slot/{slot['id']}"
        appointment_status = appointment_by_slot.get(slot_reference)

        if appointment_status in {"fulfilled", "noshow"}:
            slot["status"] = "busy"
        else:
            slot["status"] = "free"

    # =========================================================
    # DAT-02: Clinical resources
    # =========================================================

    encounters, conditions, observations = generate_clinical_resources(
        seed=seed,
        appointments=appointments,
    )

    # =========================================================
    # DAT-02: DocumentReferences and document files
    # =========================================================

    document_references, document_files = generate_documents(
        scale=scale,
        seed=seed,
        patients=patients,
        practitioners=practitioners,
        encounters=encounters,
    )

    # =========================================================
    # Write JSON resources
    # =========================================================

    hashes = {
        # DAT-01
        "patients.json": write_json(
            output_dir / "patients.json",
            patients,
        ),
        "practitioners.json": write_json(
            output_dir / "practitioners.json",
            practitioners,
        ),
        "practitioner_roles.json": write_json(
            output_dir / "practitioner_roles.json",
            roles,
        ),
        # DAT-02: Scheduling
        "schedules.json": write_json(
            output_dir / "schedules.json",
            schedules,
        ),
        "slots.json": write_json(
            output_dir / "slots.json",
            slots,
        ),
        # DAT-02: Appointments
        "appointments.json": write_json(
            output_dir / "appointments.json",
            appointments,
        ),
        # DAT-02: Clinical
        "encounters.json": write_json(
            output_dir / "encounters.json",
            encounters,
        ),
        "conditions.json": write_json(
            output_dir / "conditions.json",
            conditions,
        ),
        "observations.json": write_json(
            output_dir / "observations.json",
            observations,
        ),
        # DAT-02: Documents
        "document_references.json": write_json(
            output_dir / "document_references.json",
            document_references,
        ),
    }

    # =========================================================
    # Write generated document files
    # =========================================================

    document_hash = hashlib.sha256()

    for relative_path, content in sorted(document_files.items()):
        file_path = output_dir / relative_path
        file_hash = write_text(file_path, content)

        document_hash.update(relative_path.encode("utf-8"))
        document_hash.update(b"\0")
        document_hash.update(file_hash.encode("ascii"))
        document_hash.update(b"\n")

    hashes["documents/"] = document_hash.hexdigest()

    # =========================================================
    # Manifest: DAT-01 + DAT-02
    # =========================================================

    manifest: Manifest = {
        "generator": "DAT-02",
        "scale": scale,
        "seed": seed,
        "counts": {
            "patients": len(patients),
            "practitioners": len(practitioners),
            "practitioner_roles": len(roles),
            "schedules": len(schedules),
            "slots": len(slots),
            "appointments": len(appointments),
            "encounters": len(encounters),
            "conditions": len(conditions),
            "observations": len(observations),
            "document_references": len(document_references),
            "document_files": len(document_files),
        },
        "sha256": hashes,
    }

    write_json(output_dir / "manifest.json", manifest)

    # =========================================================
    # Console summary
    # =========================================================

    print("Synthetic data generation: SUCCESS")
    print("Generator: DAT-01 + DAT-02")
    print(f"Scale: {scale}")
    print(f"Seed: {seed}")
    print(f"Output: {output_dir}")

    print("\nCounts:")

    for resource, count in manifest["counts"].items():
        print(f"  {resource}: {count}")

    print("\nSHA-256:")

    for filename, digest in hashes.items():
        print(f"  {filename}: {digest}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="DAT-01/DAT-02 Vietnamese synthetic data generator"
    )

    parser.add_argument(
        "--scale",
        choices=["small", "medium", "large"],
        default="small",
        help="Dataset scale (default: small)",
    )

    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Integer random seed (default: 42)",
    )

    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT,
        help="Output directory (default: dev/.local/synthetic)",
    )

    args = parser.parse_args()

    generate(
        scale=args.scale,
        seed=args.seed,
        output_root=args.output_dir,
    )


if __name__ == "__main__":
    main()
