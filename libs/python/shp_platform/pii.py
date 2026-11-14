"""Che PII trước khi gửi văn bản ra LLM ngoài Azure (PY-06, NFR-SEC8).

Thay giá trị nhạy cảm bằng token giả ([PHONE_1], [PERSON_1] ...) và giữ bản đồ ngược để khôi phục câu trả lời.
Bắt bằng mẫu: email, số điện thoại Việt Nam, CCCD/CMND, mã thẻ BHYT, UUID (id hồ sơ), ngày tháng.
Họ tên và địa chỉ không bắt được bằng mẫu, nên người gọi PHẢI truyền các giá trị đã biết trong hồ sơ (`known`).
Bản đồ ngược chỉ nằm trong bộ nhớ của request, không ghi log, không lưu DB.
"""

import re
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field

# Thứ tự quan trọng: mẫu dài/cụ thể trước để không bị mẫu ngắn cắt mất.
_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("EMAIL", re.compile(r"\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b")),
    ("ID", re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")),
    ("INSURANCE", re.compile(r"\b[A-Z]{2}\d{13}\b")),  # mã thẻ BHYT: 2 chữ + 13 số
    ("NATIONAL_ID", re.compile(r"(?<!\d)(?:\d{12}|\d{9})(?!\d)")),  # CCCD 12 số, CMND 9 số
    ("PHONE", re.compile(r"(?<![\w+])(?:\+?84|0)(?:[\s.-]?\d){9}(?!\d)")),
    ("DATE", re.compile(r"\b(?:\d{1,2}[/-]\d{1,2}[/-]\d{4}|\d{4}-\d{2}-\d{2})\b")),
]


@dataclass
class Redaction:
    text: str
    reverse: dict[str, str] = field(repr=False)  # token → giá trị gốc; repr=False để không lọt log

    def restore(self, output: str) -> str:
        """Khôi phục token trong câu trả lời của LLM (token lạ do model bịa thì giữ nguyên)."""
        for token in sorted(self.reverse, key=len, reverse=True):
            output = output.replace(token, self.reverse[token])
        return output


def redact(text: str, known: Mapping[str, Iterable[str]] | None = None) -> Redaction:
    """`known`: {"PERSON": ["Nguyễn Văn A"], "ADDRESS": ["12 Lê Lợi, Q1"]}. Cùng giá trị luôn ra cùng token."""
    forward: dict[str, str] = {}
    counters: dict[str, int] = {}

    def token_for(kind: str, value: str) -> str:
        key = f"{kind}\x00{value}"
        if key not in forward:
            counters[kind] = counters.get(kind, 0) + 1
            forward[key] = f"[{kind}_{counters[kind]}]"
        return forward[key]

    def replacer(kind: str, fixed: str | None = None) -> Callable[[re.Match[str]], str]:
        return lambda m: token_for(kind, fixed if fixed is not None else m.group(0))

    out = text
    for kind, values in (known or {}).items():
        for value in sorted({v.strip() for v in values if v and len(v.strip()) >= 2}, key=len, reverse=True):
            out = re.sub(re.escape(value), replacer(kind, value), out, flags=re.IGNORECASE)
    for kind, pattern in _PATTERNS:
        out = pattern.sub(replacer(kind), out)
    reverse = {tok: key.split("\x00", 1)[1] for key, tok in forward.items()}
    return Redaction(out, reverse)


def contains_pii(text: str) -> bool:
    """Kiểm tra nhanh (test, guardrail đầu ra): còn mẫu PII nào không."""
    return any(p.search(text) for _, p in _PATTERNS)


_REDACTED = "[REDACTED]"


def mask_pii(text: str) -> str:
    """Mask PII in a log message with the common token ``[REDACTED]`` (no restore, no data kind).

    Mirrors Java ``PiiMaskingConverter`` (shp-service-defaults): same patterns, same token.
    Names and addresses cannot be caught by patterns, so they must still be kept out of logs at the source.
    """
    out = text
    for _, pattern in _PATTERNS:
        out = pattern.sub(_REDACTED, out)
    return out
