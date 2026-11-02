"""Đếm token gần đúng: mỗi từ (chữ, số) hoặc dấu câu là một token, cộng thêm cho từ dài (tiếng Việt có dấu).

Dùng để chia chunk và giới hạn ngữ cảnh; con số thật do provider trả về ở `usage` (ghi vào messages và usage).
"""

import re

_PIECE = re.compile(r"\s*(\w+|[^\w\s])", re.UNICODE)


def split_tokens(text: str) -> list[str]:
    """Các mảnh giữ nguyên khoảng trắng phía trước để ghép lại đúng văn bản gốc."""
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
