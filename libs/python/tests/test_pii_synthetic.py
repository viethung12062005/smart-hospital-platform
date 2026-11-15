"""PY-06: the PII scrubber against the shapes the synthetic-data spec guarantees (docs/00-synthetic-data.md §3).

The audit found the scrubber covered by a single hand-written sentence. These cases use the exact fake formats the
generators emit (phone range 09000xxxxx, `.test` email, CCCD starting 000, BHYT `TEST` prefix, `(giả)` address), so a
regression in either the generator or the scrubber shows up here.
"""

import pytest

from shp_platform.pii import Redaction, contains_pii, mask_pii, redact

# One realistic free-text note carrying every fake field the generators produce.
NOTE = (
    "Bệnh nhân Nguyễn Văn An (test), SĐT +84900012345, email an.nguyen@example.test, "
    "CCCD 000123456789, BHYT TEST12345678901, địa chỉ (giả) Phường Bến Nghé, Quận 1. "
    "Khám ngày 2026-11-20, mã hồ sơ 0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01."
)

KNOWN = {
    "PERSON": ["Nguyễn Văn An (test)"],
    "ADDRESS": ["(giả) Phường Bến Nghé, Quận 1"],
}


def test_every_fake_field_shape_is_removed() -> None:
    out = redact(NOTE, KNOWN).text
    for leaked in [
        "+84900012345",
        "an.nguyen@example.test",
        "000123456789",
        "TEST12345678901",
        "2026-11-20",
        "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01",
        "Nguyễn Văn An",
        "Phường Bến Nghé",
    ]:
        assert leaked not in out, f"{leaked!r} survived redaction: {out}"


def test_tokens_are_stable_and_restorable() -> None:
    first = redact(NOTE, KNOWN)
    second = redact(NOTE, KNOWN)
    assert first.text == second.text
    assert first.restore(first.text) == NOTE


def test_fake_values_produce_the_expected_token_kinds() -> None:
    out = redact(NOTE, KNOWN).text
    for kind in ("PERSON", "ADDRESS", "EMAIL", "PHONE", "NATIONAL_ID", "INSURANCE", "DATE", "ID"):
        assert f"[{kind}_" in out, f"{kind} token missing: {out}"


def test_the_fake_phone_range_is_matched() -> None:
    for phone in ["0900000000", "0900049999", "+84900000001", "0900 000 001"]:
        assert "[PHONE_" in redact(f"gọi {phone}").text, phone


def test_ordinary_clinical_numbers_are_not_treated_as_pii() -> None:
    # Blood pressure, temperature, weight and a dosage must survive: over-redaction hides clinical content.
    text = "Huyết áp 120/80, nhiệt độ 37.2 độ, nặng 65 kg, uống 2 viên mỗi ngày trong 7 ngày."
    assert redact(text).text == text


def test_the_same_value_keeps_the_same_token_across_a_note() -> None:
    r = redact("SĐT 0900012345, gọi lại 0900012345", {})
    assert r.text.count("[PHONE_1]") == 2
    assert "[PHONE_2]" not in r.text


def test_known_values_shorter_than_two_characters_are_ignored() -> None:
    r = redact("A và B", {"PERSON": ["A"]})
    assert r.text == "A và B"


def test_reverse_map_never_appears_in_repr() -> None:
    r = redact(NOTE, KNOWN)
    assert "Nguyễn Văn An" not in repr(r)
    assert isinstance(r, Redaction)


def test_restore_leaves_tokens_the_model_invented_alone() -> None:
    r = redact("gọi 0900012345", {})
    assert r.restore("[PHONE_1] và [PHONE_9]") == "0900012345 và [PHONE_9]"


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("email x@example.test", True),
        ("CCCD 000123456789", True),
        ("mã 0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01", True),
        ("không có gì nhạy cảm ở đây", False),
        ("huyết áp 120/80", False),
    ],
)
def test_contains_pii_matches_the_same_rules(text: str, expected: bool) -> None:
    assert contains_pii(text) is expected


def test_mask_pii_removes_the_values_it_claims() -> None:
    masked = mask_pii(NOTE)
    assert "an.nguyen@example.test" not in masked
    assert "000123456789" not in masked
    assert "+84900012345" not in masked
