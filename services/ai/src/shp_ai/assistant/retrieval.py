"""Truy xuất hybrid (AI-06; 06 §1.2): BM25 top 50 + vector top 50 → Reciprocal Rank Fusion → top 5.

Security trimming do index thực hiện trong truy vấn (Scope). Embedding lỗi → chỉ BM25 (xuống cấp có kiểm soát).
Ngưỡng liên quan: đoạn phải chứa ≥ 60% từ nội dung (bỏ hư từ) của câu hỏi, hoặc rất gần về vector;
không đạt → không có đoạn nào
(→ answered=false, không gọi LLM sinh nội dung, AC-11.1). Reranker cross-encoder là tùy chọn theo kết quả eval (AI-16)
và chưa bật.
"""

import re
from dataclasses import dataclass

from shp_ai.kb.embedding import Embedder, EmbeddingError
from shp_ai.kb.index import Hit, IndexedChunk, Scope, SearchIndex

CANDIDATES = 50
TOP_K = 5
RRF_K = 60
MIN_COSINE = 0.35
STRONG_COSINE = 0.75  # vector rất gần (embedding thật) thì giữ dù ít trùng từ
MIN_TERM_COVERAGE = 0.6  # chọn theo eval AI-16: từ chối đúng 1,0, Recall@5 0,91 (dữ liệu giả; đo lại với bge-m3)
_WORD = re.compile(r"\w+", re.UNICODE)
# Hư từ và từ quá phổ biến trong tài liệu y khoa tiếng Việt: không dùng để quyết định "có liên quan"
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
    have = content_terms(f"{chunk.section} {chunk.content}")  # không tính tiêu đề: từ chung của cả tài liệu
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
