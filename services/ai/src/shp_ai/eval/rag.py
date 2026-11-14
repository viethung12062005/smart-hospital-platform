"""RAG evaluation harness (AI-14, AI-15, AI-16, AI-18; NFR-AI1, NFR-AI2, NFR-AI7).

Metrics:
- Recall@5, MRR: the gold passage is in the retrieved top 5 / reciprocal rank (questions with an answer).
- Groundedness: fraction of answerable questions answered with a verified citation (verbatim quote match).
- Citation accuracy: among answered questions, the fraction whose citation points to the gold item.
- Correct refusal: fraction of unanswerable questions returned with answered=false.

  python -m shp_ai.eval.rag --out reports/rag            # retrieval + "oracle" model (pipeline check, runs in CI)
  python -m shp_ai.eval.rag --out reports/rag --live     # each real model in the chain (needs keys), report per model
"""

import argparse
import json
import re
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from shp_ai.assistant.retrieval import content_terms, retrieve
from shp_ai.assistant.service import TASK, ask
from shp_ai.eval.corpus import DOCS, UNANSWERABLE, markdown
from shp_ai.kb import parsers
from shp_ai.kb.chunker import chunk
from shp_ai.kb.embedding import Embedder, HashingEmbedder
from shp_ai.kb.index import IndexedChunk, MemoryIndex, Scope, SearchIndex
from shp_ai.llm_config import DEFAULT
from shp_platform.llm import ChainStep, ChatMessage, FakeProvider, Provider, Router, TaskConfig

THRESHOLDS = {"recallAt5": 0.8, "mrr": 0.6, "groundedness": 0.9, "citationAccuracy": 0.9, "refusalAccuracy": 0.9}
ALL_DEPARTMENTS = tuple(sorted({d.department for d in DOCS}))


@dataclass(frozen=True)
class Question:
    id: str
    question: str
    answerable: bool
    document: str | None
    section: str | None
    part: int


def questions() -> list[Question]:
    out: list[Question] = []
    for d in DOCS:
        for f in d.facts:
            out.append(Question(f"q{len(out) + 1:03d}", f.question, True, d.key, f.section, 0))
    answerable = [
        Question(q.id, q.question, True, q.document, q.section, 1 if i < 50 else 2) for i, q in enumerate(out)
    ]
    refusals = [Question(f"u{i + 1:03d}", q, False, None, None, 2) for i, q in enumerate(UNANSWERABLE)]
    return answerable + refusals


QUESTION_SCHEMA = {
    "type": "object",
    "required": ["id", "question", "answerable", "part"],
    "properties": {
        "id": {"type": "string", "pattern": "^[qu][0-9]{3}$"},
        "question": {"type": "string", "minLength": 5, "maxLength": 1000},
        "answerable": {"type": "boolean"},
        "document": {"type": ["string", "null"]},
        "section": {"type": ["string", "null"]},
        "part": {"enum": [1, 2]},
    },
    "additionalProperties": False,
}


def build_index(index: SearchIndex, embedder: Embedder) -> list[IndexedChunk]:
    chunks: list[IndexedChunk] = []
    for d in DOCS:
        pieces = chunk(parsers.parse(markdown(d), parsers.MARKDOWN))
        vectors = embedder.embed([f"{d.title}\n{p.section}\n{p.text}" for p in pieces])
        chunks += [
            IndexedChunk(
                f"{d.key}:1:{p.ordinal}", d.key, 1, d.title, p.section, p.page, d.department, d.roles, p.text, tuple(v)
            )
            for p, v in zip(pieces, vectors, strict=True)
        ]
    index.upsert(chunks)
    return chunks


def _gold(c: IndexedChunk, q: Question) -> bool:
    return c.document_id == q.document and bool(q.section) and c.section.endswith(q.section or "")


def oracle(msgs: Sequence[ChatMessage]) -> str:
    """'oracle' model for CI: answer with the first sentence of passage 1 if it shares content words with the question.

    Measures the pipeline (retrieval, relevance threshold, citation checks), not model quality; real models are
    measured with --live.
    """
    user = msgs[-1].content
    q = re.search(r"<question>\n(.*?)\n</question>", user, re.S)
    docs = re.findall(r'<document index="(\d+)"[^>]*>\n(.*?)\n</document>', user, re.S)
    if not q or not docs:
        return json.dumps({"answered": False, "answer": "", "citations": []})
    words = content_terms(q.group(1))
    idx, body = docs[0]
    text = body.split("\n", 1)[-1] if body.startswith("> ") else body
    line = next((ln for ln in text.splitlines() if ln.strip() and not ln.startswith(">")), text)
    if not words & content_terms(line):
        return json.dumps({"answered": False, "answer": "Không có thông tin.", "citations": []}, ensure_ascii=False)
    return json.dumps(
        {
            "answered": True,
            "answer": f"{line.strip()} [{idx}]",
            "citations": [{"index": int(idx), "quote": line.strip()[:120]}],
        },
        ensure_ascii=False,
    )


def run(router: Router, index: SearchIndex, embedder: Embedder, qs: list[Question]) -> dict[str, Any]:
    scope = Scope("doctor", ALL_DEPARTMENTS)
    hits5 = answered_ok = cited_ok = answered = refused = 0
    rr = 0.0
    rows = []
    ans_q = [q for q in qs if q.answerable]
    for q in qs:
        found = retrieve(index, embedder, q.question, scope, k=5)
        rank = next((i for i, c in enumerate(found.chunks, start=1) if _gold(c, q)), None)
        res = ask(router, index, embedder, q.question, scope)
        if q.answerable:
            hits5 += int(rank is not None)
            rr += 1 / rank if rank else 0
            if res.answered:
                answered += 1
                answered_ok += 1
                ids = {c["chunkId"] for c in res.citations}
                cited_ok += int(any(_gold(c, q) for c in found.chunks if c.id in ids))
        else:
            refused += int(not res.answered)
        rows.append({"id": q.id, "rank": rank, "answered": res.answered, "mode": res.mode, "guardrail": res.guardrail})
    n = len(ans_q) or 1
    metrics = {
        "recallAt5": round(hits5 / n, 4),
        "mrr": round(rr / n, 4),
        "groundedness": round(answered_ok / n, 4),
        "citationAccuracy": round(cited_ok / answered, 4) if answered else 0.0,
        "refusalAccuracy": round(refused / max(1, len(qs) - len(ans_q)), 4),
        "questions": len(qs),
        "unanswerable": len(qs) - len(ans_q),
    }
    return {"metrics": metrics, "passed": {k: metrics[k] >= v for k, v in THRESHOLDS.items()}, "rows": rows}


def oracle_router() -> Router:
    return Router(
        [FakeProvider("oracle", external=False, reply=oracle)],
        {TASK: TaskConfig([ChainStep("oracle", "oracle", False)], "retrieval-only")},
    )


def single_model_router(provider: Provider, model: str) -> Router:
    return Router([provider], {TASK: TaskConfig([ChainStep(provider.name, model)], "retrieval-only")})


def report_markdown(results: dict[str, dict[str, Any]]) -> str:
    lines = [
        "# RAG evaluation report",
        "",
        "Data: SYNTHETIC knowledge base (shp_ai/eval/corpus.py).",
        "",
        "| Model | Recall@5 | MRR | Groundedness | Citation accuracy | Correct refusal | Pass |",
        "|---|---|---|---|---|---|---|",
    ]
    for name, r in results.items():
        m = r["metrics"]
        lines.append(
            f"| {name} | {m['recallAt5']} | {m['mrr']} | {m['groundedness']} | {m['citationAccuracy']} | "
            f"{m['refusalAccuracy']} | {'yes' if all(r['passed'].values()) else 'NO'} |"
        )
    lines += ["", "Thresholds: " + ", ".join(f"{k} ≥ {v}" for k, v in THRESHOLDS.items())]
    return "\n".join(lines) + "\n"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--live", action="store_true", help="evaluate each real model in the rag-answer chain (AI-18)")
    ap.add_argument("--ollama", default="", help="Ollama URL to use bge-m3 instead of the hashing embedder")
    a = ap.parse_args()
    embedder: Embedder = HashingEmbedder()
    if a.ollama:
        from shp_ai.kb.embedding import OllamaEmbedder

        embedder = OllamaEmbedder(a.ollama)
    index = MemoryIndex()
    build_index(index, embedder)
    qs = questions()
    results: dict[str, dict[str, Any]] = {"oracle (pipeline check)": run(oracle_router(), index, embedder, qs)}
    if a.live:
        from shp_ai.llm_config import providers_from
        from shp_ai.settings import Settings

        for p in providers_from(Settings()):
            model = next(s["model"] for s in DEFAULT["tasks"][TASK]["chain"] if s["provider"] == p.name)
            results[f"{p.name}/{model}"] = run(single_model_router(p, model), index, embedder, qs)
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "rag-eval.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    (a.out / "rag-eval.md").write_text(report_markdown(results), encoding="utf-8")
    print(report_markdown(results))


if __name__ == "__main__":
    main()
