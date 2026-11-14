"""Chunker (AI-03; 06 §1.2): 600 tokens, 80 overlap, split by heading first, then by length.

A chunk never contains text from two different sections. Tokens are counted approximately by words and punctuation
(tokens.count); the difference from the model's tokenizer is covered by the context limit at prompt assembly.
"""

from dataclasses import dataclass

from shp_ai.kb.parsers import Block
from shp_ai.tokens import count, split_tokens

MAX_TOKENS = 600
OVERLAP = 80


@dataclass(frozen=True)
class Chunk:
    ordinal: int
    section: str
    page: int
    text: str
    tokens: int


def chunk(blocks: list[Block], max_tokens: int = MAX_TOKENS, overlap: int = OVERLAP) -> list[Chunk]:
    if overlap >= max_tokens:
        raise ValueError("overlap must be smaller than max_tokens")
    # merge consecutive blocks from the same section (PDF splits by page) but remember the first page
    merged: list[tuple[str, int, str]] = []
    for b in blocks:
        if merged and merged[-1][0] == b.section:
            merged[-1] = (b.section, merged[-1][1], merged[-1][2] + "\n" + b.text)
        else:
            merged.append((b.section, b.page, b.text))
    out: list[Chunk] = []
    for section, page, text in merged:
        pieces = split_tokens(text)
        if not pieces:
            continue
        start = 0
        while start < len(pieces):
            end = min(start + max_tokens, len(pieces))
            body = "".join(pieces[start:end]).strip()
            if body:
                out.append(Chunk(len(out), section, page, body, count(body)))
            if end == len(pieces):
                break
            start = end - overlap
    return out
