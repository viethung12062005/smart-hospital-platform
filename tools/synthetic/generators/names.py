"""Vietnamese name parts shared by the generators; given and middle names follow gender (ARC-11 §3)."""

import random
import unicodedata

SURNAMES = [
    "Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Huỳnh", "Phan", "Vũ", "Võ", "Đặng",
    "Bùi", "Đỗ", "Hồ", "Ngô", "Dương", "Lý", "Đinh", "Mai", "Trịnh", "Đào",
]  # fmt: skip

MIDDLE_NAMES = {
    "male": [
        "Văn",
        "Hữu",
        "Đức",
        "Minh",
        "Quốc",
        "Gia",
        "Thanh",
        "Xuân",
        "Tuấn",
        "Bảo",
    ],
    "female": [
        "Thị",
        "Ngọc",
        "Thu",
        "Thanh",
        "Minh",
        "Bảo",
        "Kim",
        "Xuân",
        "Phương",
        "Khánh",
    ],
}

GIVEN_NAMES = {
    "male": [
        "An", "Bình", "Cường", "Dũng", "Hiếu", "Hùng", "Huy", "Khang", "Khoa", "Long",
        "Minh", "Nam", "Phúc", "Phong", "Quân", "Sơn", "Thành", "Tuấn", "Việt", "Đức",
    ],
    "female": [
        "Anh", "Chi", "Dung", "Giang", "Hà", "Hạnh", "Hương", "Lan", "Linh", "Mai",
        "Ngân", "Nga", "Ngọc", "Nhung", "Quỳnh", "Thảo", "Trang", "Uyên", "Vy", "Yến",
    ],
}  # fmt: skip


def nfc(value: str) -> str:
    """Return the NFC form so the same name always has the same bytes."""
    return unicodedata.normalize("NFC", value)


def random_name(rng: random.Random, gender: str) -> tuple[str, list[str], str]:
    """Return family, given names [middle, given] and full name for a gender (male/female)."""
    family = nfc(rng.choice(SURNAMES))
    given = [
        nfc(rng.choice(MIDDLE_NAMES[gender])),
        nfc(rng.choice(GIVEN_NAMES[gender])),
    ]
    return family, given, nfc(" ".join([family, *given]))


def slug(value: str) -> str:
    """Convert Vietnamese text into an ASCII slug for email addresses."""
    value = value.replace("đ", "d").replace("Đ", "D")
    ascii_value = (
        unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii")
    )
    return "-".join(ascii_value.lower().split())
