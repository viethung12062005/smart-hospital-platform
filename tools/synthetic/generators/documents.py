"""Generate deterministic fake FHIR DocumentReference resources and SVG files."""

import random
from datetime import datetime, timedelta
from html import escape
from typing import Any

from tools.synthetic.generators.ids import UUID7Generator

DOCUMENT_COUNTS = {
    "small": 100,
    "medium": 5_000,
    "large": 50_000,
}

LOINC_DOCUMENT_SYSTEM = "http://loinc.org"

DOCUMENT_TYPES = (
    ("11502-2", "Laboratory report", "Phiếu kết quả xét nghiệm"),
    ("18748-4", "Diagnostic imaging study", "Kết quả chẩn đoán hình ảnh"),
    ("57833-6", "Prescription for medication", "Đơn thuốc mô phỏng"),
    ("57133-1", "Referral note", "Phiếu chuyển tuyến"),
    ("34133-9", "Summarization of episode note", "Tóm tắt lượt khám"),
    ("51851-4", "Administrative note", "Tài liệu hành chính"),
)

DOCUMENT_CONTENT = (
    "Đây là tài liệu mô phỏng phục vụ kiểm thử hệ thống.",
    "Thông tin trong tài liệu hoàn toàn giả lập, không dùng cho chăm sóc thực tế.",
    "Nội dung chỉ phục vụ kiểm thử quy trình lưu trữ và truy xuất tài liệu.",
)


def _make_svg(
    document_id: str,
    title: str,
    body: str,
    sequence: int,
) -> str:
    """Create a simple UTF-8 SVG document visibly marked as synthetic."""
    safe_title = escape(title)
    safe_body = escape(body)
    safe_id = escape(document_id)

    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg"
     width="900" height="600" viewBox="0 0 900 600">
  <rect width="900" height="600" fill="#ffffff"/>
  <rect x="30" y="30" width="840" height="540"
        fill="none" stroke="#333333" stroke-width="2"/>
  <text x="450" y="100" text-anchor="middle"
        font-family="sans-serif" font-size="34" font-weight="bold">
    DỮ LIỆU GIẢ
  </text>
  <line x1="80" y1="130" x2="820" y2="130"
        stroke="#777777" stroke-width="2"/>
  <text x="80" y="190" font-family="sans-serif"
        font-size="26" font-weight="bold">{safe_title}</text>
  <text x="80" y="250" font-family="sans-serif"
        font-size="19">{safe_body}</text>
  <text x="80" y="330" font-family="sans-serif"
        font-size="16">Document ID: {safe_id}</text>
  <text x="80" y="370" font-family="sans-serif"
        font-size="16">Synthetic sequence: {sequence}</text>
  <text x="450" y="500" text-anchor="middle"
        font-family="sans-serif" font-size="18" fill="#555555">
    KHÔNG PHẢI HỒ SƠ Y TẾ THẬT
  </text>
</svg>
"""


def generate_documents(
    scale: str,
    seed: int,
    patients: list[dict[str, Any]],
    practitioners: list[dict[str, Any]],
    encounters: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[str, str]]:
    """Generate DocumentReference resources and their SVG file contents.

    Returns:
        A tuple containing:
        - DocumentReference resources.
        - A mapping of relative file paths to UTF-8 SVG contents.
    """
    if scale not in DOCUMENT_COUNTS:
        raise ValueError(f"Scale không hợp lệ: {scale}")

    if not patients:
        raise ValueError("Danh sách Patient không được rỗng")

    if not practitioners:
        raise ValueError("Danh sách Practitioner không được rỗng")

    count = DOCUMENT_COUNTS[scale]

    rng = random.Random(seed + 100_000)
    document_ids = UUID7Generator(seed=seed + 100_000)

    patient_by_id = {patient["id"]: patient for patient in patients}

    practitioner_by_id = {
        practitioner["id"]: practitioner for practitioner in practitioners
    }

    # Build valid encounter-to-patient and encounter-to-practitioner links.
    encounter_context: list[tuple[dict[str, Any], str, str]] = []

    for encounter in encounters:
        patient_ref = encounter["subject"]["reference"]
        patient_id = patient_ref.split("/", 1)[1]

        if patient_id not in patient_by_id:
            raise ValueError(
                f"Encounter tham chiếu Patient không tồn tại: {patient_id}"
            )

        participants = encounter.get("participant", [])
        if not participants:
            continue

        practitioner_ref = participants[0]["individual"]["reference"]
        practitioner_id = practitioner_ref.split("/", 1)[1]

        if practitioner_id not in practitioner_by_id:
            raise ValueError(
                f"Encounter tham chiếu Practitioner không tồn tại: {practitioner_id}"
            )

        encounter_context.append((encounter, patient_id, practitioner_id))

    if not encounter_context:
        raise ValueError("Cần ít nhất một Encounter hợp lệ để sinh tài liệu")

    references: list[dict[str, Any]] = []
    files: dict[str, str] = {}

    for index in range(count):
        document_id = str(document_ids.next())

        encounter, patient_id, practitioner_id = rng.choice(encounter_context)

        code, display, title = rng.choice(DOCUMENT_TYPES)
        body = rng.choice(DOCUMENT_CONTENT)

        # Use the encounter start as the simulated upload time.
        encounter_start = datetime.fromisoformat(encounter["period"]["start"])
        uploaded_at = encounter_start + timedelta(minutes=rng.randint(1, 180))

        relative_path = f"documents/{document_id}.svg"

        svg_content = _make_svg(
            document_id=document_id,
            title=title,
            body=body,
            sequence=index + 1,
        )

        content_size = len(svg_content.encode("utf-8"))
        files[relative_path] = svg_content

        reference = {
            "resourceType": "DocumentReference",
            "id": document_id,
            "status": "current",
            "docStatus": "final",
            "type": {
                "coding": [
                    {
                        "system": LOINC_DOCUMENT_SYSTEM,
                        "code": code,
                        "display": display,
                    }
                ],
                "text": title,
            },
            "subject": {
                "reference": f"Patient/{patient_id}",
            },
            "date": uploaded_at.isoformat(),
            "author": [
                {
                    "reference": f"Practitioner/{practitioner_id}",
                }
            ],
            "content": [
                {
                    "attachment": {
                        "contentType": "image/svg+xml",
                        "url": relative_path,
                        "size": content_size,
                        "title": title,
                    }
                }
            ],
            "context": {
                "encounter": [
                    {
                        "reference": f"Encounter/{encounter['id']}",
                    }
                ],
            },
        }

        references.append(reference)

    return references, files
