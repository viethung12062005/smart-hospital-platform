"""Generate deterministic fake Vietnamese FHIR Practitioner resources."""

import random
import unicodedata
from typing import Any

from tools.synthetic.generators.ids import UUID7Generator

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

SPECIALTY_SYSTEM = (
    "https://shp.example.org/fhir/CodeSystem/specialty"
)

# Domain model quy định code doctor/nurse nhưng chưa khai báo URL CodeSystem.
ROLE_SYSTEM = "urn:shp:practitioner-role"

SURNAMES = [
    "Nguyễn", "Trần", "Lê", "Phạm", "Hoàng",
    "Huỳnh", "Phan", "Vũ", "Võ", "Đặng",
    "Bùi", "Đỗ", "Hồ", "Ngô", "Dương",
]

MIDDLE_NAMES = [
    "Văn", "Thị", "Hữu", "Đức", "Minh",
    "Ngọc", "Thanh", "Quốc", "Gia", "Hoàng",
]

GIVEN_NAMES = [
    "An", "Bình", "Cường", "Dũng", "Hà",
    "Hạnh", "Hiếu", "Hòa", "Hùng", "Khánh",
    "Lan", "Linh", "Long", "Mai", "Minh",
    "Nam", "Nga", "Ngọc", "Phúc", "Phong",
    "Quân", "Quỳnh", "Sơn", "Thảo", "Thành",
    "Trang", "Tuấn", "Uyên", "Việt", "Yến",
]

DOCTOR_PREFIXES = [
    "BS.",
    "ThS.BS.",
    "TS.BS.",
    "BS.CKII.",
    "BS.CKI.",
]


def _normalize(value: str) -> str:
    return unicodedata.normalize("NFC", value)


def _slug(value: str) -> str:
    value = value.replace("đ", "d").replace("Đ", "D")
    normalized = unicodedata.normalize("NFKD", value)
    ascii_value = normalized.encode("ascii", "ignore").decode("ascii")
    return "-".join(ascii_value.lower().split())


def _generate_name(rng: random.Random) -> str:
    family = rng.choice(SURNAMES)
    middle = rng.choice(MIDDLE_NAMES)
    given = rng.choice(GIVEN_NAMES)
    return _normalize(f"{family} {middle} {given}")


def generate_practitioners(
    scale: str,
    seed: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Return Practitioner and PractitionerRole FHIR resources."""
    if scale not in PRACTITIONER_COUNTS:
        raise ValueError(
            f"Scale không hợp lệ: {scale}. "
            f"Chọn một trong: {', '.join(PRACTITIONER_COUNTS)}"
        )

    count = PRACTITIONER_COUNTS[scale]

    # Dùng hai luồng ID độc lập để tránh trùng ID giữa hai resource.
    practitioner_ids = UUID7Generator(seed=seed + 10_000)
    role_ids = UUID7Generator(seed=seed + 20_000)

    rng = random.Random(seed + 30_000)

    # Quy ước dữ liệu giả: khoảng 2/3 bác sĩ, 1/3 điều dưỡng.
    doctor_count = (count * 2) // 3

    practitioners: list[dict[str, Any]] = []
    roles: list[dict[str, Any]] = []

    for index in range(1, count + 1):
        practitioner_id = str(practitioner_ids.next())
        role_id = str(role_ids.next())

        is_doctor = index <= doctor_count
        role_code = "doctor" if is_doctor else "nurse"

        full_name = _generate_name(rng)
        specialty_code = rng.choice(list(SPECIALTIES))
        specialty_display = SPECIALTIES[specialty_code]

        if is_doctor:
            prefix = rng.choice(DOCTOR_PREFIXES)
        else:
            prefix = "ĐD."

        email = (
            f"{_slug(full_name)}-nv{index:04d}@example.test"
        )

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
                            "display": (
                                "Bác sĩ" if is_doctor else "Điều dưỡng"
                            ),
                        }
                    ],
                    "text": "Bác sĩ" if is_doctor else "Điều dưỡng",
                }
            ],
        }

        practitioners.append(practitioner)
        roles.append(role)

    return practitioners, roles