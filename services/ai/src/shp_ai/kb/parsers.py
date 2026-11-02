"""Parser tài liệu tri thức (AI-02): PDF, DOCX, Markdown → các khối văn bản có tiêu đề mục và số trang.

Tiêu đề mục: Markdown `#`; DOCX style Heading; PDF dòng dạng "1.", "2.3 ...", "CHƯƠNG", hoặc dòng in hoa ngắn.
Không thực thi gì từ tài liệu: chỉ đọc văn bản. Tài liệu ảnh (PDF scan) không có văn bản → lỗi rõ ràng (kho tri thức
cần tài liệu có lớp chữ; OCR chỉ dùng cho trích xuất hồ sơ, US-059).
"""

import io
import re
from dataclasses import dataclass

PDF = "application/pdf"
DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
MARKDOWN = "text/markdown"
SUPPORTED = {PDF, DOCX, MARKDOWN}
MAX_BYTES = 30 * 1024 * 1024

_NUMBERED = re.compile(r"^(\d+(\.\d+)*\.?|[IVX]+\.|CHƯƠNG\s+\w+|Chương\s+\w+|PHẦN\s+\w+)\s+\S")


class ParseError(ValueError):
    pass


@dataclass(frozen=True)
class Block:
    section: str  # đường dẫn tiêu đề, ví dụ "4. Điều trị > 4.2 Chỉ định chuyển tuyến"
    page: int  # trang (PDF); 1 với định dạng không có trang
    text: str


def _is_heading(line: str) -> bool:
    s = line.strip()
    if not s or len(s) > 120:
        return False
    return bool(_NUMBERED.match(s)) or (s.isupper() and 3 <= len(s) <= 80 and any(c.isalpha() for c in s))


def _level(heading: str) -> int:
    m = re.match(r"^(\d+(\.\d+)*)", heading.strip())
    return m.group(1).count(".") + 1 if m else 1


class _Builder:
    def __init__(self) -> None:
        self.path: list[tuple[int, str]] = []
        self.blocks: list[Block] = []
        self.buf: list[str] = []
        self.page = 1

    def heading(self, text: str, level: int) -> None:
        self.flush()
        self.path = [p for p in self.path if p[0] < level] + [(level, text.strip())]

    def line(self, text: str) -> None:
        if text.strip():
            self.buf.append(text.strip())

    def flush(self) -> None:
        if self.buf:
            section = " > ".join(t for _, t in self.path) or "(đầu tài liệu)"
            self.blocks.append(Block(section, self.page, "\n".join(self.buf)))
            self.buf = []


def parse_markdown(data: bytes) -> list[Block]:
    b = _Builder()
    in_code = False
    for raw in data.decode("utf-8", errors="replace").splitlines():
        if raw.strip().startswith("```"):
            in_code = not in_code
            continue
        m = re.match(r"^(#{1,6})\s+(.*)$", raw)
        if m and not in_code:
            b.heading(m.group(2), len(m.group(1)))
        else:
            b.line(raw)
    b.flush()
    return b.blocks


def parse_docx(data: bytes) -> list[Block]:
    from docx import Document

    b = _Builder()
    for p in Document(io.BytesIO(data)).paragraphs:
        style = (p.style.name if p.style is not None else "") or ""
        if style.lower().startswith("heading") or style.lower().startswith("title"):
            digits = re.findall(r"\d+", style)
            b.heading(p.text, int(digits[0]) if digits else 1)
        else:
            b.line(p.text)
    b.flush()
    return b.blocks


def parse_pdf(data: bytes) -> list[Block]:
    from pypdf import PdfReader

    b = _Builder()
    reader = PdfReader(io.BytesIO(data))
    if reader.is_encrypted:
        raise ParseError("PDF có mật khẩu")
    for i, page in enumerate(reader.pages, start=1):
        b.flush()
        b.page = i
        for line in (page.extract_text() or "").splitlines():
            if _is_heading(line):
                b.heading(line, _level(line))
            else:
                b.line(line)
    b.flush()
    return b.blocks


def parse(data: bytes, content_type: str) -> list[Block]:
    if len(data) > MAX_BYTES:
        raise ParseError("tệp vượt 30 MB")
    if content_type == MARKDOWN:
        blocks = parse_markdown(data)
    elif content_type == DOCX:
        blocks = parse_docx(data)
    elif content_type == PDF:
        blocks = parse_pdf(data)
    else:
        raise ParseError(f"loại tệp không hỗ trợ: {content_type}")
    if not any(bl.text.strip() for bl in blocks):
        raise ParseError("tài liệu không có văn bản (PDF scan cần lớp chữ)")
    return blocks
