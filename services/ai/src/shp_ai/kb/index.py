"""Knowledge chunk index (AI-01, AI-04, AI-06; 06 §1.2). One interface, three implementations:

- `OpenSearchIndex` (local, LOC-08): BM25 + k-NN (HNSW, cosine, lucene engine with in-query filtering).
- `AzureSearchIndex` (cloud): index `kb-chunks-v1` per 06 §1.2, managed identity auth.
- `MemoryIndex` (tests, dev without infrastructure).

Security trimming happens INSIDE the query (06 §1.2): `allowedRoles` contains the caller's roles AND `department`
belongs to their department or is `hospital-wide`. Never fetch broadly then filter afterwards.
"""

import math
import re
from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
from typing import Any, Protocol

import httpx

HOSPITAL_WIDE = "hospital-wide"
_WORD = re.compile(r"\w+", re.UNICODE)


@dataclass(frozen=True)
class IndexedChunk:
    id: str  # <documentId>:<version>:<ordinal>
    document_id: str
    document_version: int
    title: str
    section: str
    page: int
    department: str
    allowed_roles: tuple[str, ...]
    content: str
    vector: tuple[float, ...] = field(default=(), repr=False)
    updated_at: str = field(default_factory=lambda: datetime.now(UTC).isoformat())


@dataclass(frozen=True)
class Hit:
    chunk: IndexedChunk
    score: float


@dataclass(frozen=True)
class Scope:
    """The caller's allowed scope."""

    role: str
    departments: tuple[str, ...]

    def allows(self, c: IndexedChunk) -> bool:
        return self.role in c.allowed_roles and (c.department in self.departments or c.department == HOSPITAL_WIDE)


class SearchIndex(Protocol):
    def ensure(self) -> None: ...

    def upsert(self, chunks: Sequence[IndexedChunk]) -> None: ...

    def delete_document(self, document_id: str, except_version: int | None = None) -> None: ...

    def keyword(self, query: str, scope: Scope, k: int) -> list[Hit]: ...

    def vector(self, vector: Sequence[float], scope: Scope, k: int) -> list[Hit]: ...


def _terms(text: str) -> list[str]:
    return _WORD.findall(text.lower())


class MemoryIndex:
    def __init__(self) -> None:
        self.chunks: dict[str, IndexedChunk] = {}

    def ensure(self) -> None:
        return None

    def upsert(self, chunks: Sequence[IndexedChunk]) -> None:
        for c in chunks:
            self.chunks[c.id] = c

    def delete_document(self, document_id: str, except_version: int | None = None) -> None:
        self.chunks = {
            k: c
            for k, c in self.chunks.items()
            if c.document_id != document_id or (except_version is not None and c.document_version == except_version)
        }

    def keyword(self, query: str, scope: Scope, k: int) -> list[Hit]:
        docs = [c for c in self.chunks.values() if scope.allows(c)]
        if not docs:
            return []
        q = set(_terms(query))
        tokenized = [_terms(f"{c.title} {c.section} {c.content}") for c in docs]
        avg = sum(len(t) for t in tokenized) / len(tokenized)
        df = Counter(w for t in tokenized for w in set(t))
        n = len(docs)
        hits = []
        for c, toks in zip(docs, tokenized, strict=True):
            tf = Counter(toks)
            s = 0.0
            for w in q:
                if tf[w]:
                    idf = math.log(1 + (n - df[w] + 0.5) / (df[w] + 0.5))
                    s += idf * tf[w] * 2.2 / (tf[w] + 1.2 * (0.25 + 0.75 * len(toks) / avg))
            if s > 0:
                hits.append(Hit(c, s))
        return sorted(hits, key=lambda h: -h.score)[:k]

    def vector(self, vector: Sequence[float], scope: Scope, k: int) -> list[Hit]:
        hits = [
            Hit(c, sum(a * b for a, b in zip(vector, c.vector, strict=False)))
            for c in self.chunks.values()
            if scope.allows(c) and c.vector
        ]
        return sorted(hits, key=lambda h: -h.score)[:k]


def _chunk_from(src: dict[str, Any]) -> IndexedChunk:
    return IndexedChunk(
        id=src["id"],
        document_id=src["documentId"],
        document_version=int(src["documentVersion"]),
        title=src["title"],
        section=src["section"],
        page=int(src.get("page") or 1),
        department=src["department"],
        allowed_roles=tuple(src.get("allowedRoles") or ()),
        content=src["content"],
        updated_at=str(src.get("updatedAt", "")),
    )


def _doc(c: IndexedChunk) -> dict[str, Any]:
    return {
        "id": c.id,
        "documentId": c.document_id,
        "documentVersion": c.document_version,
        "title": c.title,
        "section": c.section,
        "page": c.page,
        "department": c.department,
        "allowedRoles": list(c.allowed_roles),
        "content": c.content,
        "contentVector": list(c.vector),
        "updatedAt": c.updated_at,
    }


class OpenSearchIndex:
    def __init__(
        self, url: str, name: str = "kb-chunks-v1", dimensions: int = 1024, http: httpx.Client | None = None
    ) -> None:
        self._base = url.rstrip("/")
        self._name = name
        self._dims = dimensions
        self._http = http or httpx.Client(timeout=15.0)

    def _req(self, method: str, path: str, body: Any = None, ok: tuple[int, ...] = (200, 201)) -> dict[str, Any]:
        res = self._http.request(method, f"{self._base}/{self._name}{path}", json=body)
        if res.status_code not in ok:
            raise RuntimeError(f"opensearch {method} {path}: {res.status_code}")
        out: dict[str, Any] = res.json() if res.content else {}
        return out

    def ensure(self) -> None:
        if self._http.head(f"{self._base}/{self._name}").status_code == 200:
            return
        text = {"type": "text"}
        self._req(
            "PUT",
            "",
            {
                "settings": {"index": {"knn": True}},
                "mappings": {
                    "properties": {
                        "id": {"type": "keyword"},
                        "documentId": {"type": "keyword"},
                        "documentVersion": {"type": "integer"},
                        "title": text,
                        "section": text,
                        "content": text,
                        "page": {"type": "integer"},
                        "department": {"type": "keyword"},
                        "allowedRoles": {"type": "keyword"},
                        "updatedAt": {"type": "date"},
                        "contentVector": {
                            "type": "knn_vector",
                            "dimension": self._dims,
                            "method": {
                                "name": "hnsw",
                                "engine": "lucene",
                                "space_type": "cosinesimil",
                                "parameters": {"m": 16, "ef_construction": 400},
                            },
                        },
                    }
                },
            },
        )

    def upsert(self, chunks: Sequence[IndexedChunk]) -> None:
        if not chunks:
            return
        import json

        lines = []
        for c in chunks:
            lines.append(json.dumps({"index": {"_index": self._name, "_id": c.id}}))
            lines.append(json.dumps(_doc(c), ensure_ascii=False))
        res = self._http.post(
            f"{self._base}/_bulk?refresh=true",
            content="\n".join(lines) + "\n",
            headers={"Content-Type": "application/x-ndjson"},
        )
        if res.status_code != 200 or res.json().get("errors"):
            raise RuntimeError("opensearch bulk failed")

    def delete_document(self, document_id: str, except_version: int | None = None) -> None:
        must_not = [{"term": {"documentVersion": except_version}}] if except_version is not None else []
        self._req(
            "POST",
            "/_delete_by_query?refresh=true",
            {"query": {"bool": {"filter": [{"term": {"documentId": document_id}}], "must_not": must_not}}},
        )

    @staticmethod
    def _filter(scope: Scope) -> list[dict[str, Any]]:
        return [{"term": {"allowedRoles": scope.role}}, {"terms": {"department": [*scope.departments, HOSPITAL_WIDE]}}]

    def _hits(self, body: dict[str, Any]) -> list[Hit]:
        res = self._req("POST", "/_search", body)
        return [Hit(_chunk_from(h["_source"]), float(h["_score"])) for h in res["hits"]["hits"]]

    def keyword(self, query: str, scope: Scope, k: int) -> list[Hit]:
        return self._hits(
            {
                "size": k,
                "_source": {"excludes": ["contentVector"]},
                "query": {
                    "bool": {
                        "filter": self._filter(scope),
                        "must": [{"multi_match": {"query": query, "fields": ["content", "section^2", "title"]}}],
                    }
                },
            }
        )

    def vector(self, vector: Sequence[float], scope: Scope, k: int) -> list[Hit]:
        return self._hits(
            {
                "size": k,
                "_source": {"excludes": ["contentVector"]},
                "query": {
                    "knn": {
                        "contentVector": {
                            "vector": list(vector),
                            "k": k,
                            "filter": {"bool": {"filter": self._filter(scope)}},
                        }
                    }
                },
            }
        )


class AzureSearchIndex:  # pragma: no cover - needs Azure AI Search; exercised in UAT
    API = "2024-07-01"

    def __init__(self, endpoint: str, name: str = "kb-chunks-v1", dimensions: int = 1024) -> None:
        from azure.identity import DefaultAzureCredential

        self._cred = DefaultAzureCredential()
        self._base = endpoint.rstrip("/")
        self._name = name
        self._dims = dimensions
        self._http = httpx.Client(timeout=15.0)

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._cred.get_token('https://search.azure.com/.default').token}"}

    def _call(self, method: str, path: str, body: Any = None) -> dict[str, Any]:
        res = self._http.request(
            method, f"{self._base}{path}?api-version={self.API}", json=body, headers=self._headers()
        )
        if res.status_code >= 300:
            raise RuntimeError(f"azure search {res.status_code}")
        out: dict[str, Any] = res.json() if res.content else {}
        return out

    def ensure(self) -> None:
        searchable = {"type": "Edm.String", "searchable": True, "analyzer": "vi.microsoft"}
        self._call(
            "PUT",
            f"/indexes/{self._name}",
            {
                "name": self._name,
                "fields": [
                    {"name": "id", "type": "Edm.String", "key": True, "filterable": True},
                    {"name": "documentId", "type": "Edm.String", "filterable": True},
                    {"name": "documentVersion", "type": "Edm.Int32", "filterable": True},
                    {"name": "title", **searchable},
                    {"name": "section", **searchable},
                    {"name": "page", "type": "Edm.Int32"},
                    {"name": "department", "type": "Edm.String", "filterable": True, "facetable": True},
                    {"name": "allowedRoles", "type": "Collection(Edm.String)", "filterable": True},
                    {"name": "content", **searchable},
                    {
                        "name": "contentVector",
                        "type": "Collection(Edm.Single)",
                        "searchable": True,
                        "dimensions": self._dims,
                        "vectorSearchProfile": "hnsw-cosine",
                    },
                    {"name": "updatedAt", "type": "Edm.DateTimeOffset", "filterable": True},
                ],
                "vectorSearch": {
                    "algorithms": [
                        {
                            "name": "hnsw",
                            "kind": "hnsw",
                            "hnswParameters": {"metric": "cosine", "m": 16, "efConstruction": 400},
                        }
                    ],
                    "profiles": [{"name": "hnsw-cosine", "algorithm": "hnsw"}],
                },
            },
        )

    def upsert(self, chunks: Sequence[IndexedChunk]) -> None:
        self._call(
            "POST",
            f"/indexes/{self._name}/docs/index",
            {"value": [{"@search.action": "mergeOrUpload", **_doc(c)} for c in chunks]},
        )

    def delete_document(self, document_id: str, except_version: int | None = None) -> None:
        flt = f"documentId eq '{_odata(document_id)}'"
        if except_version is not None:
            flt += f" and documentVersion ne {int(except_version)}"
        found = self._call("POST", f"/indexes/{self._name}/docs/search", {"filter": flt, "select": "id", "top": 1000})[
            "value"
        ]
        if found:
            self._call(
                "POST",
                f"/indexes/{self._name}/docs/index",
                {"value": [{"@search.action": "delete", "id": d["id"]} for d in found]},
            )

    @staticmethod
    def _filter(scope: Scope) -> str:
        deps = " or ".join(f"department eq '{_odata(d)}'" for d in (*scope.departments, HOSPITAL_WIDE))
        return f"allowedRoles/any(r: r eq '{_odata(scope.role)}') and ({deps})"

    def _search(self, body: dict[str, Any]) -> list[Hit]:
        res = self._call("POST", f"/indexes/{self._name}/docs/search", body)
        return [Hit(_chunk_from(v), float(v["@search.score"])) for v in res["value"]]

    def keyword(self, query: str, scope: Scope, k: int) -> list[Hit]:
        return self._search(
            {
                "search": query,
                "filter": self._filter(scope),
                "top": k,
                "select": "id,documentId,documentVersion,title,section,page,department,allowedRoles,content",
            }
        )

    def vector(self, vector: Sequence[float], scope: Scope, k: int) -> list[Hit]:
        return self._search(
            {
                "vectorQueries": [{"kind": "vector", "vector": list(vector), "fields": "contentVector", "k": k}],
                "filter": self._filter(scope),
                "top": k,
                "select": "id,documentId,documentVersion,title,section,page,department,allowedRoles,content",
            }
        )


def _odata(value: str) -> str:
    """Escape quotes in an OData filter (prevents filter injection)."""
    return value.replace("'", "''")


def with_vector(c: IndexedChunk, v: Sequence[float]) -> IndexedChunk:
    return replace(c, vector=tuple(v))
