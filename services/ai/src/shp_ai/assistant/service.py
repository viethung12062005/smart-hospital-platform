"""Generate RAG answers with citations (AI-08; 12 §2, BR-09, AC-11.1..11.3).

input check → retrieval (trimming) → no relevant passage: answered=false, NO LLM call → assemble context ≤ 3,500
tokens in a <documents> block → router (DeepSeek → Gemini → Ollama, PII redacted when leaving) → output check (schema,
verbatim quote, no directives) → on failure retry once with the next model → still failing: answered=false.
All models fail: retrieval-only mode (return the list of relevant items). Budget exhausted: budget_exceeded.
"""

import html
import re
import time
from collections.abc import Callable, Generator
from dataclasses import dataclass, field
from typing import Any

from shp_ai import prompts
from shp_ai.assistant.answer_stream import AnswerExtractor
from shp_ai.assistant.guardrails import Answer, OutputRejectedError, check_input, check_output, parse_answer
from shp_ai.assistant.retrieval import Retrieved, retrieve
from shp_ai.kb.embedding import Embedder
from shp_ai.kb.index import IndexedChunk, Scope, SearchIndex
from shp_ai.tokens import count, truncate
from shp_platform.llm import ChatMessage, Router, StreamDelta, StreamReset
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


@dataclass(frozen=True)
class TokenEvent:
    text: str
    provider: str = ""
    model: str = ""


@dataclass(frozen=True)
class ResetEvent:
    """Discard every token shown so far: `model-switch` (the model failed mid-answer) or `guardrail` (the finished
    answer failed the output check)."""

    reason: str


@dataclass(frozen=True)
class FinalEvent:
    result: "AskResult"


AskEvent = TokenEvent | ResetEvent | FinalEvent


def ask_stream(
    router: Router,
    index: SearchIndex,
    embedder: Embedder,
    question: str,
    scope: Scope,
    result: AskResult | None = None,
    clock: Callable[[], float] = time.monotonic,
    on_first_token: Callable[[str, str, float], None] | None = None,
) -> Generator[AskEvent]:
    """`ask`, delivering the answer while the model writes it (AI-09).

    Yields `TokenEvent`s (the `answer` text of the model's JSON reply, placeholders restored), a `ResetEvent` whenever
    text already shown must be discarded, and finally exactly one `FinalEvent` with the same `AskResult` that `ask`
    would return. The decision logic (retry once with the next model on a rejected reply, retrieval-only when the
    chain is exhausted) is the one of `ask`. `result` is filled in place so the caller can account for the
    attempts even when it closes the generator early (client disconnected).
    `on_first_token(provider, model, seconds)`: seconds from the start of this call (input check, retrieval, prompt
    build included) to the first token shown, measured with `clock` (NFR-P5: first token <= 3 s).
    """
    started = clock()
    result = result if result is not None else AskResult(False, NO_ANSWER, [])
    inp = check_input(question)
    if not inp.ok:
        result.mode, result.guardrail = "guardrail", f"blocked:{inp.reason}"
        yield FinalEvent(result)
        return
    found: Retrieved = retrieve(index, embedder, question, scope)
    result.retrieval_mode = found.mode
    if not found.chunks:
        result.mode = "no-context"
        yield FinalEvent(result)
        return
    messages, used, version = build_messages(question, found.chunks)
    texts = [c.content for c in used]
    result.prompt_version = version
    level = 0
    rejected: str | None = None
    visible = False  # tokens are on the client's screen and not retracted yet
    first_token_seen = False
    for _ in range(2):  # first attempt + one retry with the next model when the output fails
        stream = router.stream(TASK, messages, start_level=level)
        r = stream.result
        result.attempts.append(r)
        result.router = r
        extractor = AnswerExtractor()
        try:
            for ev in stream:
                if isinstance(ev, StreamDelta):
                    text = extractor.feed(ev.text)
                    if text:
                        if not first_token_seen:
                            first_token_seen = True
                            if on_first_token is not None:
                                on_first_token(ev.provider, ev.model, clock() - started)
                        visible = True
                        yield TokenEvent(text, ev.provider, ev.model)
                elif isinstance(ev, StreamReset):
                    extractor = AnswerExtractor()
                    if visible:
                        visible = False
                        yield ResetEvent("model-switch")
        finally:
            stream.close()  # no-op when finished; when the client left, stops the provider and accounts tokens
        if r.budget_exceeded:
            result.budget_exceeded = True
            break
        if not r.answered:
            break
        try:
            ans: Answer = check_output(parse_answer(r.text), texts)
        except OutputRejectedError as exc:
            rejected = str(exc)
            if visible:
                visible = False
                yield ResetEvent("guardrail")
            level = r.fallback_level + 1
            if level >= router.chain_length(TASK):
                break
            continue
        final_text = ans.answer if ans.answered else NO_ANSWER
        if visible and extractor.text.strip() != final_text:  # e.g. the model said answered=false but wrote text
            visible = False
            yield ResetEvent("guardrail")
        if not visible:
            yield TokenEvent(final_text, r.provider, r.model)
        result.answered = ans.answered
        result.answer = final_text
        result.citations = [citation(c["index"], used[c["index"] - 1], c["quote"]) for c in ans.citations]
        result.mode = "answer"
        yield FinalEvent(result)
        return
    if rejected is not None and result.router is not None and result.router.answered:
        result.guardrail = f"blocked:{rejected}"
        result.mode = "guardrail"
        yield TokenEvent(NO_ANSWER)
        yield FinalEvent(result)
        return
    if visible:  # a stream that ended without an answer after text was shown (defensive: the router resets first)
        yield ResetEvent("model-switch")
    result.mode = "retrieval-only"
    result.citations = [citation(i, c) for i, c in enumerate(used, start=1)]
    yield FinalEvent(result)
