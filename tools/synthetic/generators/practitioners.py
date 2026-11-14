"""Generate deterministic fake Vietnamese FHIR Practitioner resources."""

import random
from typing import Any

from tools.synthetic.generators.ids import UUID7Generator
from tools.synthetic.generators.names import random_name, slug

PRACTITIONER_COUNTS = {
    "small": 12,
    "medium": 120,
    "large": 600,
}

SPECIALTIES = {
    "internal-medicine": "Nội tổng quát",
    "cardiology": "Tim mạch",
    "pediatrics": "Nhi",
    "obstetrics-gynecology": "Sản phụ khoa",
    "dermatology": "Da liễu",
    "ent": "Tai mũi họng",
    "ophthalmology": "Mắt",
    "orthopedics": "Chấn thương chỉnh hình",
    "neurology": "Thần kinh",
    "gastroenterology": "Tiêu hóa",
    "endocrinology": "Nội tiết",
    "general-surgery": "Ngoại tổng quát",
}

SPECIALTY_SYSTEM = "https://shp.example.org/fhir/CodeSystem/specialty"

# The practitioner-role CodeSystem lives in contracts/terminology (01-domain-model §3, §4.2).
ROLE_SYSTEM = "https://shp.example.org/fhir/CodeSystem/practitioner-role"


DOCTOR_PREFIXES = [
    "BS.",
    "ThS.BS.",
    "TS.BS.",
    "BS.CKII.",
    "BS.CKI.",
]


def generate_practitioners(
    scale: str,
    seed: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Return Practitioner and PractitionerRole FHIR resources."""
    if scale not in PRACTITIONER_COUNTS:
        raise ValueError(
            f"Invalid scale: {scale}. Choose one of: {', '.join(PRACTITIONER_COUNTS)}"
        )

    count = PRACTITIONER_COUNTS[scale]

    # Use two independent ID streams to avoid duplicate IDs between the two resources.
    practitioner_ids = UUID7Generator(seed=seed + 10_000)
    role_ids = UUID7Generator(seed=seed + 20_000)

    rng = random.Random(seed + 30_000)

    # Fake data convention: about 2/3 doctors, 1/3 nurses.
    doctor_count = (count * 2) // 3

    practitioners: list[dict[str, Any]] = []
    roles: list[dict[str, Any]] = []

    for index in range(1, count + 1):
        practitioner_id = str(practitioner_ids.next())
        role_id = str(role_ids.next())

        is_doctor = index <= doctor_count
        role_code = "doctor" if is_doctor else "nurse"

        gender = rng.choices(["male", "female"], weights=[49, 51], k=1)[0]
        _, _, full_name = random_name(rng, gender)
        specialty_code = rng.choice(list(SPECIALTIES))
        specialty_display = SPECIALTIES[specialty_code]

        if is_doctor:
            prefix = rng.choice(DOCTOR_PREFIXES)
        else:
            prefix = "ĐD."

        email = f"{slug(full_name)}-nv{index:04d}@example.test"

        practitioner = {
            "resourceType": "Practitioner",
            "id": practitioner_id,
            "identifier": [
                {
                    "system": "urn:shp:practitioner",
                    "value": f"NV-{index:04d}",
                }
            ],
            "active": True,
            "name": [
                {
                    "text": full_name,
                    "prefix": [prefix],
                }
            ],
            "telecom": [
                {
                    "system": "email",
                    "value": email,
                    "use": "work",
                }
            ],
            "gender": gender,
        }

        role = {
            "resourceType": "PractitionerRole",
            "id": role_id,
            "practitioner": {
                "reference": f"Practitioner/{practitioner_id}",
                "display": f"{prefix} {full_name}",
            },
            "active": True,
            "specialty": [
                {
                    "coding": [
                        {
                            "system": SPECIALTY_SYSTEM,
                            "code": specialty_code,
                            "display": specialty_display,
                        }
                    ],
                    "text": specialty_display,
                }
            ],
            "code": [
                {
                    "coding": [
                        {
                            "system": ROLE_SYSTEM,
                            "code": role_code,
                            "display": ("Bác sĩ" if is_doctor else "Điều dưỡng"),
                        }
                    ],
                    "text": "Bác sĩ" if is_doctor else "Điều dưỡng",
                }
            ],
        }

        practitioners.append(practitioner)
        roles.append(role)

    return practitioners, roles
