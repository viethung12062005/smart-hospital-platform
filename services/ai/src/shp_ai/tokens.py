"""Approximate token counting: each word (letters, digits) or punctuation mark is a token, plus extra for long words
(Vietnamese with diacritics).

Used to split chunks and limit context; the real numbers come from the provider's `usage` (recorded in messages and
usage).
"""

import re

_PIECE = re.compile(r"\s*(\w+|[^\w\s])", re.UNICODE)


def split_tokens(text: str) -> list[str]:
    """Fragments that keep leading whitespace so the original text can be reassembled exactly."""
    return [m.group(0) for m in _PIECE.finditer(text)]


def count(text: str) -> int:
    return sum(1 + len(m.group(1)) // 8 for m in _PIECE.finditer(text))


def truncate(text: str, max_tokens: int) -> str:
    out, used = [], 0
    for m in _PIECE.finditer(text):
        cost = 1 + len(m.group(1)) // 8
        if used + cost > max_tokens:
            break
        out.append(m.group(0))
        used += cost
    return "".join(out)
