"""Truy xuất hybrid (AI-06; 06 §1.2): BM25 top 50 + vector top 50 → Reciprocal Rank Fusion → top 5.

Security trimming do index thực hiện trong truy vấn (Scope). Embedding lỗi → chỉ BM25 (xuống cấp có kiểm soát).
Ngưỡng liên quan: chunk phải có khớp từ khóa hoặc độ tương đồng vector ≥ MIN_COSINE; không đạt → không có đoạn nào
(→ answered=false, không gọi LLM sinh nội dung, AC-11.1). Reranker cross-encoder là tùy chọn theo kết quả eval (AI-16)
và chưa bật.
"""

from dataclasses import dataclass

from shp_ai.kb.embedding import Embedder, EmbeddingError
from shp_ai.kb.index import Hit, IndexedChunk, Scope, SearchIndex

CANDIDATES = 50
TOP_K = 5
RRF_K = 60
MIN_COSINE = 0.35


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
    ranked = sorted(fused.values(), key=lambda x: -x[0])[:k]
    return Retrieved([c for _, c in ranked], [round(s, 6) for s, _ in ranked], mode)


def hits_to_ids(hits: list[Hit]) -> list[str]:
    return [h.chunk.id for h in hits]
