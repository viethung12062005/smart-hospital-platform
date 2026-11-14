"""Generate RAG answers with citations (AI-08; 12 §2, BR-09, AC-11.1..11.3).

input check → retrieval (trimming) → no relevant passage: answered=false, NO LLM call → assemble context ≤ 3,500
tokens in a <documents> block → router (DeepSeek → Gemini → Ollama, PII redacted when leaving) → output check (schema,
verbatim quote, no directives) → on failure retry once with the next model → still failing: answered=false.
All models fail: retrieval-only mode (return the list of relevant items). Budget exhausted: budget_exceeded.
"""

import html
import re
from dataclasses import dataclass, field
from typing import Any

from shp_ai import prompts
from shp_ai.assistant.guardrails import Answer, OutputRejectedError, check_input, check_output, parse_answer
from shp_ai.assistant.retrieval import Retrieved, retrieve
from shp_ai.kb.embedding import Embedder
from shp_ai.kb.index import IndexedChunk, Scope, SearchIndex
from shp_ai.tokens import count, truncate
from shp_platform.llm import ChatMessage, Router
from shp_platform.llm.router import RouterResult

TASK = "rag-answer"
CONTEXT_TOKENS = 3500
NO_ANSWER = "Không có thông tin trong tài liệu được phép truy cập."
DISCLAIMER = "Thông tin tham khảo từ tài liệu nội bộ đã duyệt, không thay thế quyết định của bác sĩ."


@dataclass
class AskResult:
    answered: bool
    answer: str
    citations: list[dict[str, Any]]
    mode: str = "answer"  # answer | no-context | retrieval-only | guardrail
    guardrail: str = "passed"
    router: RouterResult | None = None
    attempts: list[RouterResult] = field(default_factory=list)
    prompt_version: str = ""
    retrieval_mode: str = ""
    budget_exceeded: bool = False


_TAG = re.compile(r"<\s*/?\s*(documents?|question|system|assistant|user)\b[^>]*>", re.IGNORECASE)


def neutralize(text: str) -> str:
    """Document content must not close/open the prompt's data block (prevents injection via documents)."""
    return _TAG.sub("[thẻ đã bị loại]", text)


def citation(i: int, c: IndexedChunk, quote: str = "") -> dict[str, Any]:
    return {
        "index": i,
        "chunkId": c.id,
        "documentId": c.document_id,
        "title": c.title,
        "section": c.section,
        "page": c.page,
        "quote": quote,
    }


def build_messages(question: str, chunks: list[IndexedChunk]) -> tuple[list[ChatMessage], list[IndexedChunk], str]:
    version, system = prompts.load(TASK)
    used: list[IndexedChunk] = []
    parts: list[str] = []
    budget = CONTEXT_TOKENS
    for c in chunks:
        body = (
            f'<document index="{len(used) + 1}" title="{html.escape(c.title)}" section="{html.escape(c.section)}">'
            f"\n{neutralize(c.content)}\n</document>"
        )
        cost = count(body)
        if cost > budget:
            if not used:  # first passage too long: truncate instead of dropping everything
                body = truncate(body, budget)
            else:
                break
        used.append(c)
        parts.append(body)
        budget -= cost
    user = f"<documents>\n{chr(10).join(parts)}\n</documents>\n<question>\n{question}\n</question>"
    return [ChatMessage("system", system), ChatMessage("user", user)], used, version


def ask(router: Router, index: SearchIndex, embedder: Embedder, question: str, scope: Scope) -> AskResult:
    inp = check_input(question)
    if not inp.ok:
        return AskResult(False, NO_ANSWER, [], mode="guardrail", guardrail=f"blocked:{inp.reason}")
    found: Retrieved = retrieve(index, embedder, question, scope)
    if not found.chunks:
        return AskResult(False, NO_ANSWER, [], mode="no-context", retrieval_mode=found.mode)
    messages, used, version = build_messages(question, found.chunks)
    texts = [c.content for c in used]
    result = AskResult(False, NO_ANSWER, [], prompt_version=version, retrieval_mode=found.mode)
    level = 0
    rejected: str | None = None
    for _ in range(2):  # first attempt + one retry with the next model when the output fails
        r = router.complete(TASK, messages, start_level=level)
        result.attempts.append(r)
        result.router = r
        if r.budget_exceeded:
            result.budget_exceeded = True
            break
        if not r.answered:
            break
        try:
            ans: Answer = check_output(parse_answer(r.text), texts)
        except OutputRejectedError as exc:
            rejected = str(exc)
            level = r.fallback_level + 1
            if level >= router.chain_length(TASK):
                break
            continue
        result.answered = ans.answered
        result.answer = ans.answer if ans.answered else NO_ANSWER
        result.citations = [citation(c["index"], used[c["index"] - 1], c["quote"]) for c in ans.citations]
        result.mode = "answer"
        return result
    if rejected is not None and result.router is not None and result.router.answered:
        result.guardrail = f"blocked:{rejected}"
        result.mode = "guardrail"
        return result
    # every model failed or the budget is exhausted: return only the list of relevant items (no generated content)
    result.mode = "retrieval-only"
    result.citations = [citation(i, c) for i, c in enumerate(used, start=1)]
    return result
