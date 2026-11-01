"""Tóm tắt hồ sơ (AI-20, AI-21; 12 §3, AC-12.1..12.3).

Bản ghi đánh số [E1]..[En] → LLM tóm tắt theo schema cố định → kiểm tra: mỗi ý phải có `sources` trỏ tới bản ghi có
thật; con số trong ý phải xuất hiện trong bản ghi nguồn; không có câu chẩn đoán/chỉ định trực tiếp hay chỉ dẫn gửi AI.
Ý không đạt bị LOẠI (AC-12.2). Mọi model lỗi hoặc đầu ra hỏng → chế độ `structured-timeline` dựng thẳng từ dữ liệu.
"""

import html
import json
import re
from dataclasses import dataclass, field
from typing import Any

from shp_ai import prompts
from shp_ai.assistant.guardrails import is_clinical_directive, looks_like_instruction
from shp_ai.assistant.service import neutralize
from shp_ai.summary.clinical import Evidence
from shp_platform.llm import ChatMessage, Router
from shp_platform.llm.router import RouterResult

TASK = "patient-summary"
SECTIONS = ("chronicConditions", "allergies", "recentVisits", "abnormalResults")
AI_LABEL = "Do AI tạo, cần bác sĩ kiểm tra"
TIMELINE_LABEL = "Danh sách có cấu trúc từ hồ sơ (không qua AI)"
_NUM = re.compile(r"\d+(?:[.,]\d+)?")


@dataclass
class SummaryResult:
    mode: str  # ai | structured-timeline
    sections: dict[str, list[dict[str, Any]]]
    dropped: int = 0
    router: RouterResult | None = None
    prompt_version: str = ""
    attempts: list[RouterResult] = field(default_factory=list)


def _record_block(ev: list[Evidence]) -> str:
    lines = [
        f'<record id="{e.key}" type="{e.kind}" time="{html.escape(e.time)}">{neutralize(e.text)}</record>' for e in ev
    ]
    return "<records>\n" + "\n".join(lines) + "\n</records>"


def structured(ev: list[Evidence], limit: int = 10) -> dict[str, list[dict[str, Any]]]:
    pick = {
        "chronicConditions": "condition",
        "allergies": "allergy",
        "recentVisits": "encounter",
        "abnormalResults": "observation",
    }
    out: dict[str, list[dict[str, Any]]] = {}
    for section, kind in pick.items():
        items = [e for e in ev if e.kind == kind and (kind != "observation" or e.abnormal)]
        items.sort(key=lambda e: e.time, reverse=True)
        out[section] = [{"text": e.text, "sources": [e.ref], "time": e.time} for e in items[:limit]]
    return out


def _valid_item(item: Any, by_key: dict[str, Evidence]) -> dict[str, Any] | None:
    if not isinstance(item, dict) or not isinstance(item.get("text"), str) or not isinstance(item.get("sources"), list):
        return None
    text = item["text"].strip()
    keys = [s for s in item["sources"] if isinstance(s, str) and s in by_key]
    if not text or not keys or len(keys) != len(item["sources"]) or len(text) > 400:
        return None
    if looks_like_instruction(text) or is_clinical_directive(text):
        return None
    source_text = " ".join(f"{by_key[k].text} {by_key[k].time}" for k in keys)
    if any(n not in source_text for n in _NUM.findall(text)):
        return None  # con số không có trong nguồn: có thể là bịa
    return {"text": text, "sources": [by_key[k].ref for k in keys]}


def parse_sections(raw: str, ev: list[Evidence]) -> tuple[dict[str, list[dict[str, Any]]], int]:
    s = raw.strip()
    start, end = s.find("{"), s.rfind("}")
    data = json.loads(s[start : end + 1]) if 0 <= start < end else None
    if not isinstance(data, dict):
        raise ValueError("schema")
    by_key = {e.key: e for e in ev}
    out: dict[str, list[dict[str, Any]]] = {}
    dropped = 0
    for section in SECTIONS:
        items = data.get(section, [])
        if not isinstance(items, list):
            raise ValueError("schema")
        kept = [v for v in (_valid_item(i, by_key) for i in items) if v is not None]
        dropped += len(items) - len(kept)
        out[section] = kept
    return out, dropped


def summarize(router: Router, ev: list[Evidence], practitioner_names: list[str]) -> SummaryResult:
    if not ev:
        return SummaryResult("structured-timeline", structured(ev))
    version, system = prompts.load(TASK)
    messages = [ChatMessage("system", system), ChatMessage("user", _record_block(ev))]
    attempts: list[RouterResult] = []
    level = 0
    for _ in range(2):
        r = router.complete(TASK, messages, known_pii={"PERSON": practitioner_names}, start_level=level)
        attempts.append(r)
        if not r.answered:
            break
        try:
            sections, dropped = parse_sections(r.text, ev)
        except (ValueError, json.JSONDecodeError):
            level = r.fallback_level + 1
            if level >= router.chain_length(TASK):
                break
            continue
        return SummaryResult("ai", sections, dropped, r, version, attempts)
    return SummaryResult(
        "structured-timeline",
        structured(ev),
        router=attempts[-1] if attempts else None,
        prompt_version=version,
        attempts=attempts,
    )
