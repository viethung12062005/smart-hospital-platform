"""Command-line interface for generating synthetic FHIR data."""

import argparse
import hashlib
import json
from pathlib import Path
from typing import TypedDict

from tools.synthetic.generators.patients import (
    PATIENT_COUNTS,
    generate_patients,
)
from tools.synthetic.generators.practitioners import (
    generate_practitioners,
)


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
    path.write_bytes(encoded)

    return hashlib.sha256(encoded).hexdigest()


def generate(scale: str, seed: int, output_root: Path) -> None:
    """Generate all DAT-01 resources."""
    if scale not in PATIENT_COUNTS:
        raise ValueError(f"Invalid scale: {scale}")

    output_dir = output_root / f"{scale}-seed-{seed}"
    output_dir.mkdir(parents=True, exist_ok=True)

    patients = generate_patients(scale=scale, seed=seed)

    practitioners, roles = generate_practitioners(
        scale=scale,
        seed=seed,
    )

    hashes = {
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
    }

    manifest: Manifest = {
        "generator": "DAT-01",
        "scale": scale,
        "seed": seed,
        "counts": {
            "patients": len(patients),
            "practitioners": len(practitioners),
            "practitioner_roles": len(roles),
        },
        "sha256": hashes,
    }

    write_json(output_dir / "manifest.json", manifest)

    print("Synthetic data generation: SUCCESS")
    print(f"Scale: {scale}")
    print(f"Seed: {seed}")
    print(f"Output: {output_dir}")
    print("\nCounts:")

    counts = manifest["counts"]

    for resource, count in counts.items():
        print(f"  {resource}: {count}")

    print("\nSHA-256:")

    for filename, digest in hashes.items():
        print(f"  {filename}: {digest}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="DAT-01 Vietnamese synthetic data generator"
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
