"""Generate deterministic fake Vietnamese FHIR Patient resources."""

import random
import unicodedata
from datetime import date, timedelta
from typing import Any

from tools.synthetic.generators.ids import UUID7Generator

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

SURNAMES = [
    "Nguyễn", "Trần", "Lê", "Phạm", "Hoàng",
    "Huỳnh", "Phan", "Vũ", "Võ", "Đặng",
    "Bùi", "Đỗ", "Hồ", "Ngô", "Dương",
    "Lý", "Đinh", "Mai", "Trịnh", "Đào",
]

MIDDLE_NAMES = [
    "Văn", "Thị", "Hữu", "Đức", "Minh",
    "Ngọc", "Thanh", "Quốc", "Gia", "Hoàng",
    "Xuân", "Tuấn", "Khánh", "Bảo", "Anh",
]

GIVEN_NAMES = [
    "An", "Bình", "Cường", "Dũng", "Hà",
    "Hạnh", "Hiếu", "Hòa", "Hùng", "Khánh",
    "Lan", "Linh", "Long", "Mai", "Minh",
    "Nam", "Nga", "Ngọc", "Phúc", "Phong",
    "Quân", "Quỳnh", "Sơn", "Thảo", "Thành",
    "Trang", "Tuấn", "Uyên", "Việt", "Yến",
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


def _slug(value: str) -> str:
    """Convert Vietnamese text into an ASCII email slug."""
    value = value.removesuffix(" (test)")
    value = value.replace("đ", "d").replace("Đ", "D")
    normalized = unicodedata.normalize("NFKD", value)
    ascii_value = normalized.encode("ascii", "ignore").decode("ascii")
    return "-".join(ascii_value.lower().split())


def _generate_name(
    rng: random.Random,
) -> tuple[str, list[str], str]:
    """Return family, given names, and full name."""
    family = rng.choice(SURNAMES)
    middle = rng.choice(MIDDLE_NAMES)
    given = rng.choice(GIVEN_NAMES)

    given_parts = [middle, given]
    full_name = f"{family} {middle} {given}"

    # NFC bảo đảm tên tiếng Việt ở dạng Unicode chuẩn.
    full_name = unicodedata.normalize("NFC", full_name)
    family = unicodedata.normalize("NFC", family)
    given_parts = [
        unicodedata.normalize("NFC", part)
        for part in given_parts
    ]

    return family, given_parts, full_name


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
            f"Scale không hợp lệ: {scale}. "
            f"Chọn một trong: {', '.join(PATIENT_COUNTS)}"
        )

    count = PATIENT_COUNTS[scale]
    rng = random.Random(seed)
    id_generator = UUID7Generator(seed=seed)

    patients: list[dict[str, Any]] = []

    for index in range(1, count + 1):
        patient_id = str(id_generator.next())

        family, given, full_name = _generate_name(rng)

        if scale == "small":
            full_name = f"{full_name} (test)"

        gender = rng.choices(
            ["male", "female"],
            weights=[49, 51],
            k=1,
        )[0]

        birth_date = _generate_age(rng)

        # Dải số giả 09000xxxxx theo ARC-11.
        # E.164: bỏ số 0 đầu và thêm mã quốc gia +84.
        phone_suffix = (index - 1) % 100_000
        phone = f"+849000{phone_suffix:05d}"

        email_slug = _slug(full_name)
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