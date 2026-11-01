"""Guardrail đầu vào (AI-10) và đầu ra (AI-11) cho trợ lý (12 §2, US-054).

Đầu vào: độ dài 3-1.000 ký tự, mẫu prompt injection phổ biến (tiếng Việt và tiếng Anh).
Không chặn câu hỏi y khoa thường.
Đầu ra: đúng schema JSON; mọi [n] trong câu trả lời có trích dẫn; `quote` khớp nguyên văn đoạn n; không có câu chẩn đoán
hay chỉ định điều trị trực tiếp cho người bệnh; con số liều dùng phải xuất hiện trong đoạn được trích.
"""

import json
import re
import unicodedata
from dataclasses import dataclass
from typing import Any

MIN_LEN, MAX_LEN = 3, 1000

_INJECTION = [
    r"(bỏ qua|phớt lờ|quên)\s+(hết\s+|mọi\s+|tất cả\s+|các\s+)?(hướng dẫn|chỉ dẫn|quy tắc|lệnh)",
    r"ignore\s+(all\s+|any\s+|the\s+)?(previous|prior|above|earlier)?\s*(instructions|rules|prompts?)",
    r"disregard\s+(all\s+|the\s+)?(previous|above|prior)",
    r"(system|developer)\s*prompt",
    r"(tiết lộ|in ra|hiển thị|cho (tôi )?xem)\s+(nội dung\s+)?(prompt|chỉ dẫn hệ thống|hướng dẫn hệ thống)",
    r"reveal\s+(your\s+)?(prompt|instructions|system)",
    r"\bjailbreak\b",
    r"\bDAN\b",
    r"developer\s+mode",
    r"</?\s*(system|documents?|question|assistant)\s*>",
    r"(bây giờ|từ giờ)\s+bạn\s+(là|đóng vai)",
    r"you\s+are\s+now\s+",
    r"act\s+as\s+(an?\s+)?(unrestricted|unfiltered)",
    r"không\s+(cần\s+)?tuân (theo|thủ)\s+(quy tắc|hướng dẫn)",
]
_INJECTION_RE = [re.compile(p, re.IGNORECASE) for p in _INJECTION]

# Câu nói với AI/model (chỉ dẫn cài trong tài liệu): không bao giờ là nội dung phác đồ hợp lệ
_ADDRESSING_AI = [
    r"\b(trợ lý|AI|model|mô hình|assistant|chatbot)\b[^.\n]{0,40}\b(phải|hãy|cần|must|should|shall)\b",
    r"\b(nếu bạn là|if you are)\s+(một\s+)?(AI|model|trợ lý|assistant)\b",
    r"\b(instruction|note|ghi chú)\s+(to|for|cho)\s+(the\s+)?(AI|model|assistant|trợ lý)\b",
    r"(mọi|tất cả)\s+câu trả lời\s+(phải|cần)",
    r"\b(output|print|respond with|reply|say)\b\s+\S",
    r"\boverride\b|\bact\s+as\b|forget\s+the\s+(documents?|rules|instructions)",
    r"\banswered\s*[=:]\s*true\b|\"answered\"\s*:",
    r"\[(SYSTEM|INST)\]|ghi chú ẩn|developer note",
]
_ADDRESSING_RE = [re.compile(p, re.IGNORECASE) for p in _ADDRESSING_AI]


def looks_like_instruction(text: str) -> bool:
    """Văn bản có dạng chỉ dẫn gửi AI (prompt injection), dùng cho câu hỏi, đoạn tài liệu và câu trả lời."""
    norm = unicodedata.normalize("NFC", text)
    return any(r.search(norm) for r in _INJECTION_RE) or any(r.search(norm) for r in _ADDRESSING_RE)


_DIRECT_CLINICAL = [
    r"\b(bạn|anh|chị|ông|bà|bệnh nhân này)\s+(bị|mắc|đã mắc)\s+",  # không có "em": "trẻ em bị" là câu tài liệu
    r"\b(tôi|ai)\s+chẩn đoán\b",
    r"\bchẩn đoán (của tôi|xác định) là\b",
    r"\b(bạn|anh|chị|ông|bà) (nên|hãy|cần) (uống|dùng|tiêm|ngưng|ngừng|tăng liều|giảm liều)\b",
    r"\bkê (đơn|toa)\b",
]
_DIRECT_RE = [re.compile(p, re.IGNORECASE) for p in _DIRECT_CLINICAL]
_DOSE = re.compile(r"\d+(?:[.,]\d+)?\s?(?:mg|mcg|µg|g|ml|mL|UI|IU|đơn vị)\b", re.IGNORECASE)
_REF = re.compile(r"\[(\d{1,2})\]")


@dataclass(frozen=True)
class InputCheck:
    ok: bool
    reason: str = ""


def check_input(question: str) -> InputCheck:
    q = question.strip()
    if len(q) < MIN_LEN or len(q) > MAX_LEN:
        return InputCheck(False, "length")
    norm = unicodedata.normalize("NFC", q)
    if looks_like_instruction(norm):
        return InputCheck(False, "prompt-injection")
    return InputCheck(True)


def _norm(text: str) -> str:
    t = unicodedata.normalize("NFC", text).lower()
    t = re.sub(r"[\"“”'‘’`]", "", t)
    return re.sub(r"\s+", " ", t).strip()


@dataclass
class Answer:
    answered: bool
    answer: str
    citations: list[dict[str, Any]]  # {"index": n, "quote": "..."}


class OutputRejectedError(ValueError):
    pass


def parse_answer(text: str) -> Answer:
    s = text.strip()
    if s.startswith("```"):
        s = re.sub(r"^```(?:json)?\s*|\s*```$", "", s, flags=re.IGNORECASE)
    start, end = s.find("{"), s.rfind("}")
    if start < 0 or end <= start:
        raise OutputRejectedError("schema")
    try:
        data = json.loads(s[start : end + 1])
    except json.JSONDecodeError as exc:
        raise OutputRejectedError("schema") from exc
    if (
        not isinstance(data, dict)
        or not isinstance(data.get("answered"), bool)
        or not isinstance(data.get("answer"), str)
    ):
        raise OutputRejectedError("schema")
    cits = data.get("citations", [])
    if not isinstance(cits, list) or not all(
        isinstance(c, dict) and isinstance(c.get("index"), int) and isinstance(c.get("quote"), str) for c in cits
    ):
        raise OutputRejectedError("schema")
    return Answer(data["answered"], data["answer"].strip(), cits)


def check_output(ans: Answer, chunks: list[str]) -> Answer:
    """Trả lại Answer đã chuẩn hóa hoặc ném OutputRejectedError(lý do)."""
    if not ans.answered:
        return Answer(False, ans.answer, [])
    if not ans.answer or len(ans.answer) > 4000:
        raise OutputRejectedError("schema")
    if not ans.citations:
        raise OutputRejectedError("missing-citation")
    cited: dict[int, str] = {}
    for c in ans.citations:
        n = c["index"]
        if not 1 <= n <= len(chunks):
            raise OutputRejectedError("citation-out-of-range")
        quote = _norm(c["quote"])
        if len(quote) < 8 or quote not in _norm(chunks[n - 1]):
            raise OutputRejectedError("quote-mismatch")
        cited[n] = chunks[n - 1]
    refs = {int(m) for m in _REF.findall(ans.answer)}
    if not refs or not refs <= set(cited):
        raise OutputRejectedError("missing-citation")
    if looks_like_instruction(ans.answer) or any(looks_like_instruction(c["quote"]) for c in ans.citations):
        raise OutputRejectedError("injected-content")
    if any(r.search(ans.answer) for r in _DIRECT_RE):
        raise OutputRejectedError("clinical-directive")
    source = _norm(" ".join(cited.values()))
    for dose in _DOSE.findall(ans.answer):
        if _norm(dose).replace(" ", "") not in source.replace(" ", ""):
            raise OutputRejectedError("unsupported-dose")
    return ans
