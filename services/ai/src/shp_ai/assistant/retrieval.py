"""Hybrid retrieval (AI-06; 06 §1.2): BM25 top 50 + vector top 50 → Reciprocal Rank Fusion → top 5.

Security trimming is done by the index inside the query (Scope). Embedding failure → BM25 only (controlled degradation).
Relevance threshold: a passage must contain ≥ 60% of the question's content terms (stopwords removed), or be very
close in vector space;
otherwise → no passage at all
(→ answered=false, no LLM content generation, AC-11.1). A cross-encoder reranker is optional per eval results (AI-16)
and is not enabled yet.
"""

import re
from dataclasses import dataclass

from shp_ai.kb.embedding import Embedder, EmbeddingError
from shp_ai.kb.index import Hit, IndexedChunk, Scope, SearchIndex

CANDIDATES = 50
TOP_K = 5
RRF_K = 60
MIN_COSINE = 0.35
STRONG_COSINE = 0.75  # very close vector (real embedding): keep it even with little term overlap
MIN_TERM_COVERAGE = 0.6  # chosen from eval AI-16: refusal 1.0, Recall@5 0.91 (synthetic data; re-measure with bge-m3)
_WORD = re.compile(r"\w+", re.UNICODE)
# Stopwords and very common terms in Vietnamese medical documents: not used to decide "relevant"
STOPWORDS = frozenset(
    """
và của là có cho khi thì bao nhiêu lâu nào gì thế như người bệnh nhân cần được các những một lần trong sau trước
với để không nên làm ở đâu mấy ngày giờ phải theo này đó ra vào tại từ đến hay hoặc cách nào thì bị việc
phác đồ quy trình hướng dẫn điều trị xử trí tiêu chuẩn chỉ định
the a an of to is are what how when which for in on at
""".split()
)


def content_terms(text: str) -> set[str]:
    return {w for w in _WORD.findall(text.lower()) if w not in STOPWORDS and len(w) > 1}


def relevant(query_terms: set[str], chunk: IndexedChunk) -> bool:
    if not query_terms:
        return False
    # do not count the heading: words common to the whole document
    have = content_terms(f"{chunk.section} {chunk.content}")
    matched = len(query_terms & have)
    return matched >= min(2, len(query_terms)) and matched / len(query_terms) >= MIN_TERM_COVERAGE


@dataclass(frozen=True)
class Retrieved:
    chunks: list[IndexedChunk]
    scores: list[float]
    mode: str  # hybrid | keyword-only


def retrieve(index: SearchIndex, embedder: Embedder, query: str, scope: Scope, k: int = TOP_K) -> Retrieved:
    keyword = index.keyword(query, scope, CANDIDATES)
    mode = "hybrid"
    try:
        vec = embedder.embed([query])[0]
        vector = [h for h in index.vector(vec, scope, CANDIDATES) if h.score >= MIN_COSINE]
    except EmbeddingError:
        vector, mode = [], "keyword-only"
    fused: dict[str, tuple[float, IndexedChunk]] = {}
    for hits in (keyword, vector):
        for rank, h in enumerate(hits, start=1):
            prev = fused.get(h.chunk.id, (0.0, h.chunk))[0]
            fused[h.chunk.id] = (prev + 1.0 / (RRF_K + rank), h.chunk)
    terms = content_terms(query)
    vector_ids = {h.chunk.id for h in vector if h.score >= STRONG_COSINE}
    ranked = [x for x in sorted(fused.values(), key=lambda x: -x[0]) if relevant(terms, x[1]) or x[1].id in vector_ids][
        :k
    ]
    return Retrieved([c for _, c in ranked], [round(s, 6) for s, _ in ranked], mode)


def hits_to_ids(hits: list[Hit]) -> list[str]:
    return [h.chunk.id for h in hits]
