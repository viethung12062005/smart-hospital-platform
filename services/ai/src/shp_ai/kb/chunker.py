"""Chunker (AI-03; 06 §1.2): 600 token, overlap 80, tách theo tiêu đề mục trước rồi mới theo độ dài.

Một chunk không bao giờ chứa văn bản của hai mục khác nhau. Token đếm gần đúng theo từ và dấu câu (tokens.count);
sai lệch so với tokenizer của model được bù bằng giới hạn ngữ cảnh ở bước ghép prompt.
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
        raise ValueError("overlap phải nhỏ hơn max_tokens")
    # gộp các khối liên tiếp cùng mục (PDF tách theo trang) nhưng nhớ trang đầu
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
