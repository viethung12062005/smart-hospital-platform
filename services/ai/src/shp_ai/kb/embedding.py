"""Embedding (AI-04; ADR-0013): bge-m3 1024 chiều chạy local qua Ollama, văn bản không rời cụm.

`HashingEmbedder` là bản giả xác định (băm từ vào 1024 chiều) cho test và dev không có Ollama; đủ để truy xuất theo
từ khóa hoạt động, không dùng ở môi trường thật.
"""

import hashlib
import math
import re
from collections.abc import Sequence
from typing import Protocol

import httpx


class EmbeddingError(RuntimeError):
    pass


class Embedder(Protocol):
    model: str
    dimensions: int

    def embed(self, texts: Sequence[str]) -> list[list[float]]: ...


class OllamaEmbedder:
    def __init__(
        self, base_url: str, model: str = "bge-m3", dimensions: int = 1024, http: httpx.Client | None = None
    ) -> None:
        self.model = model
        self.dimensions = dimensions
        self._url = base_url.rstrip("/") + "/api/embed"
        self._http = http or httpx.Client(timeout=30.0)

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        out: list[list[float]] = []
        for i in range(0, len(texts), 16):
            try:
                res = self._http.post(self._url, json={"model": self.model, "input": list(texts[i : i + 16])})
            except httpx.HTTPError as exc:
                raise EmbeddingError(type(exc).__name__) from exc
            if res.status_code != 200:
                raise EmbeddingError(f"ollama {res.status_code}")
            vectors = res.json().get("embeddings")
            if not isinstance(vectors, list) or any(len(v) != self.dimensions for v in vectors):
                raise EmbeddingError("số chiều embedding sai")
            out.extend([float(x) for x in v] for v in vectors)
        return out


_WORD = re.compile(r"\w+", re.UNICODE)


class HashingEmbedder:
    def __init__(self, dimensions: int = 1024) -> None:
        self.model = "hashing-test"
        self.dimensions = dimensions

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        out = []
        for t in texts:
            v = [0.0] * self.dimensions
            for w in _WORD.findall(t.lower()):
                h = int.from_bytes(hashlib.blake2b(w.encode(), digest_size=8).digest(), "big")
                v[h % self.dimensions] += 1.0 if (h >> 63) == 0 else -1.0
            norm = math.sqrt(sum(x * x for x in v)) or 1.0
            out.append([x / norm for x in v])
        return out
