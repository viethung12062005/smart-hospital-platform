"""NFR-O4 / TC-SEC-11: logs must not contain PII and must carry a trace-id."""

import logging

from shp_platform.logging import PiiMaskingFormatter
from shp_platform.pii import mask_pii

TEXT = (
    "Patient born 12/03/1980, phone 0912 345 678, email an.nguyen@example.com, "
    "national id 001080012345, insurance card DN4790123456789, record id 550e8400-e29b-41d4-a716-446655440000."
)

SECRETS = [
    "0912 345 678",
    "an.nguyen@example.com",
    "001080012345",
    "DN4790123456789",
    "12/03/1980",
    "550e8400-e29b-41d4-a716-446655440000",
]


def test_mask_pii_removes_all_pattern_pii() -> None:
    out = mask_pii(TEXT)
    for secret in SECRETS:
        assert secret not in out, secret
    assert "[REDACTED]" in out


def test_mask_pii_leaves_ordinary_text_intact() -> None:
    text = "Appointment booked successfully, no sensitive data here."
    assert mask_pii(text) == text


def test_formatter_masks_pii_and_adds_trace_id() -> None:
    formatter = PiiMaskingFormatter("%(trace_id)s|%(message)s")
    record = logging.LogRecord(
        "shp.test", logging.INFO, __file__, 1, "email an.nguyen@example.com", (), None
    )
    out = formatter.format(record)
    assert "an.nguyen@example.com" not in out
    assert "[REDACTED]" in out
    # trace-id is injected: 32 hex chars when in a span, else "-"
    trace_id = out.split("|", 1)[0]
    assert trace_id == "-" or len(trace_id) == 32
