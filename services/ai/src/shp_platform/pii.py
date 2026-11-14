"""Redact PII before sending text to an LLM outside Azure (PY-06, NFR-SEC8).

Replace sensitive values with fake tokens ([PHONE_1], [PERSON_1] ...) and keep a reverse map to restore the answer.
Matched by pattern: email, Vietnamese phone numbers, CCCD/CMND, health-insurance card numbers, UUIDs (record ids),
dates.
Names and addresses cannot be matched by pattern, so the caller MUST pass the known values from the record (`known`).
The reverse map lives only in request memory; it is not logged or stored in a DB.
"""

import re
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field

# Order matters: longer/specific patterns first so shorter ones do not cut them off.
_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("EMAIL", re.compile(r"\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b")),
    ("ID", re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")),
    ("INSURANCE", re.compile(r"\b[A-Z]{2}\d{13}\b")),  # health-insurance card: 2 letters + 13 digits
    ("NATIONAL_ID", re.compile(r"(?<!\d)(?:\d{12}|\d{9})(?!\d)")),  # CCCD 12 digits, CMND 9 digits
    ("PHONE", re.compile(r"(?<![\w+])(?:\+?84|0)(?:[\s.-]?\d){9}(?!\d)")),
    ("DATE", re.compile(r"\b(?:\d{1,2}[/-]\d{1,2}[/-]\d{4}|\d{4}-\d{2}-\d{2})\b")),
]


@dataclass
class Redaction:
    text: str
    reverse: dict[str, str] = field(repr=False)  # token -> original value; repr=False so it never leaks into logs

    def restore(self, output: str) -> str:
        """Restore tokens in the LLM's answer (unknown tokens invented by the model are left as is)."""
        for token in sorted(self.reverse, key=len, reverse=True):
            output = output.replace(token, self.reverse[token])
        return output


def redact(text: str, known: Mapping[str, Iterable[str]] | None = None) -> Redaction:
    """`known`: {"PERSON": ["Nguyễn Văn A"], "ADDRESS": ["12 Lê Lợi, Q1"]}. The same value always yields the same
    token."""
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
    """Quick check (tests, output guardrail): whether any PII pattern remains."""
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
