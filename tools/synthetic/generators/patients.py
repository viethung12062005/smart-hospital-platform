"""Generate deterministic fake Vietnamese FHIR Patient resources."""

import random
from datetime import date, timedelta
from typing import Any

from tools.synthetic.generators.ids import UUID7Generator
from tools.synthetic.generators.names import random_name, slug

# Cố định ngày tham chiếu để cùng seed luôn cho cùng kết quả.
REFERENCE_DATE = date(2026, 11, 7)
MRN_YEAR = 2026

PATIENT_COUNTS = {
    "small": 200,
    "medium": 20_000,
    "large": 500_000,
}

# Phân bố tuổi theo ARC-11.
AGE_GROUPS = [
    (0, 14, 18),
    (15, 44, 35),
    (45, 64, 29),
    (65, 120, 18),
]


LOCATIONS = [
    ("Hà Nội", "Ba Đình"),
    ("Hà Nội", "Cầu Giấy"),
    ("Hà Nội", "Đống Đa"),
    ("Hà Nội", "Hai Bà Trưng"),
    ("Hà Nội", "Hoàng Mai"),
    ("Hà Nội", "Thanh Xuân"),
    ("Hồ Chí Minh", "Quận 1"),
    ("Hồ Chí Minh", "Quận 3"),
    ("Hồ Chí Minh", "Bình Thạnh"),
    ("Hồ Chí Minh", "Gò Vấp"),
    ("Đà Nẵng", "Hải Châu"),
    ("Đà Nẵng", "Thanh Khê"),
    ("Hải Phòng", "Hồng Bàng"),
    ("Cần Thơ", "Ninh Kiều"),
]


def _shift_years(value: date, years: int) -> date:
    """Shift a date by whole years, handling leap days."""
    try:
        return value.replace(year=value.year - years)
    except ValueError:
        return value.replace(year=value.year - years, day=28)


def _random_birth_date(
    rng: random.Random,
    min_age: int,
    max_age: int,
) -> date:
    """Generate a birth date inside an inclusive age range."""
    earliest = _shift_years(REFERENCE_DATE, max_age + 1) + timedelta(days=1)
    latest = _shift_years(REFERENCE_DATE, min_age)

    days = (latest - earliest).days
    return earliest + timedelta(days=rng.randint(0, days))


# Dải số giả theo ARC-11: 09000xxxxx (100.000 số) đủ cho small/medium. large cần 500.000 số nên mở rộng sang
# 0900xxxxxx (1.000.000 số, vẫn là dải chưa cấp phát); 100.000 số đầu trùng dải gốc nên small/medium không đổi.
PHONE_CAPACITY = 1_000_000


def fake_phone(index: int) -> str:
    """E.164 phone for the 1-based patient index: +84900000000, +84900000001... (unique per index)."""
    if not 1 <= index <= PHONE_CAPACITY:
        raise ValueError(f"Hết dải số giả: index {index} > {PHONE_CAPACITY}")
    return f"+84900{index - 1:06d}"


def _generate_age(rng: random.Random) -> date:
    """Choose an age group using ARC-11 weights and generate DOB."""
    group = rng.choices(
        AGE_GROUPS,
        weights=[group[2] for group in AGE_GROUPS],
        k=1,
    )[0]

    min_age, max_age, _ = group
    return _random_birth_date(rng, min_age, max_age)


def generate_patients(
    scale: str,
    seed: int,
) -> list[dict[str, Any]]:
    """Generate fake FHIR Patient resources for the selected scale."""
    if scale not in PATIENT_COUNTS:
        raise ValueError(
            f"Scale không hợp lệ: {scale}. Chọn một trong: {', '.join(PATIENT_COUNTS)}"
        )

    count = PATIENT_COUNTS[scale]
    rng = random.Random(seed)
    id_generator = UUID7Generator(seed=seed)

    patients: list[dict[str, Any]] = []

    for index in range(1, count + 1):
        patient_id = str(id_generator.next())

        gender = rng.choices(
            ["male", "female"],
            weights=[49, 51],
            k=1,
        )[0]

        family, given, full_name = random_name(rng, gender)
        email_slug = slug(full_name)

        if scale == "small":
            full_name = f"{full_name} (test)"

        birth_date = _generate_age(rng)

        phone = fake_phone(index)
        email = f"{email_slug}-{index:06d}@example.test"

        city, district = rng.choice(LOCATIONS)

        patient = {
            "resourceType": "Patient",
            "id": patient_id,
            "identifier": [
                {
                    "system": "urn:shp:mrn",
                    "value": f"BN-{MRN_YEAR}-{index:06d}",
                }
            ],
            "active": True,
            "name": [
                {
                    "use": "official",
                    "family": family,
                    "given": given,
                    "text": full_name,
                }
            ],
            "telecom": [
                {
                    "system": "phone",
                    "value": phone,
                    "use": "mobile",
                },
                {
                    "system": "email",
                    "value": email,
                },
            ],
            "gender": gender,
            "birthDate": birth_date.isoformat(),
            "address": [
                {
                    "text": f"(giả), {district}, {city}",
                    "city": city,
                    "district": district,
                }
            ],
            "communication": [
                {
                    "language": {
                        "coding": [
                            {
                                "system": "urn:ietf:bcp:47",
                                "code": "vi",
                            }
                        ]
                    }
                }
            ],
        }

        patients.append(patient)

    return patients
