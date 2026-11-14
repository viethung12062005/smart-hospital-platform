"""Knowledge document lifecycle (AI-05; 06 §1.1, 12 §6): draft → approved → indexed → retired.

- Admins upload (PDF, DOCX, Markdown ≤ 30 MB, check the file signature rather than trusting Content-Type) → `draft`.
- A doctor of the department (or an admin) approves → `approved` → index (parse, chunk, embed, upsert) → `indexed`.
  Indexing is retried 3 times; still failing keeps `approved` with `indexError` and an alert metric.
- Replacing the file → `version+1`, needs re-approval; old-version chunks keep serving until the new version is indexed.
- Remove → delete from the index, keep the record (`retired`).
"""

import hashlib
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from prometheus_client import Counter
from pymongo import ASCENDING, ReturnDocument
from pymongo.database import Database

from shp_ai.assistant.guardrails import looks_like_instruction
from shp_ai.blobstore import BlobStore
from shp_ai.kb import parsers
from shp_ai.kb.chunker import chunk
from shp_ai.kb.embedding import Embedder
from shp_ai.kb.index import HOSPITAL_WIDE, IndexedChunk, SearchIndex
from shp_platform.errors import ShpError

INDEX_ATTEMPTS = 3
ROLES = frozenset({"doctor", "nurse"})
KB_SUSPICIOUS = Counter(
    "shp_ai_kb_suspicious_chunks_total",
    "Document chunks rejected for looking like AI-directed instructions",
)
KB_INDEXED = Counter("shp_ai_kb_index_total", "Knowledge document indexing outcome", ["outcome"])
Doc = dict[str, Any]


def sniff(data: bytes, declared: str) -> str:
    """Determine the file type from its content (magic bytes)."""
    if data.startswith(b"%PDF-"):
        return parsers.PDF
    if data.startswith(b"PK\x03\x04") and b"word/" in data[:4096] + data[-65536:]:
        return parsers.DOCX
    if declared in (parsers.MARKDOWN, "text/plain") or declared.endswith("markdown"):
        try:
            data.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ShpError("invalid-file-type") from exc
        if b"\x00" in data:
            raise ShpError("invalid-file-type")
        return parsers.MARKDOWN
    raise ShpError("invalid-file-type")


@dataclass(frozen=True)
class NewDocument:
    title: str
    source: str
    department: str
    allowed_roles: tuple[str, ...]


def ensure_indexes(db: Database[Doc]) -> None:
    db.kb_documents.create_index([("status", ASCENDING), ("department", ASCENDING)])


class KnowledgeBase:
    def __init__(
        self,
        db: Database[Doc],
        blobs: BlobStore,
        index: SearchIndex,
        embedder: Embedder,
        container: str = "kb",
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self.db = db
        self.blobs = blobs
        self.index = index
        self.embedder = embedder
        self.container = container
        self.clock = clock

    # ---------------- ghi ----------------
    def upload(self, meta: NewDocument, data: bytes, declared_type: str, actor: str) -> Doc:
        if len(data) > parsers.MAX_BYTES:
            raise ShpError("file-too-large")
        if not data:
            raise ShpError("validation-failed", "Tệp rỗng.")
        if not meta.allowed_roles or not set(meta.allowed_roles) <= ROLES:
            raise ShpError("validation-failed", "allowedRoles chỉ gồm doctor, nurse.")
        ctype = sniff(data, declared_type)
        doc_id = str(uuid.uuid4())
        now = self.clock()
        doc: Doc = {
            "_id": doc_id,
            "title": meta.title,
            "source": meta.source,
            "department": meta.department,
            "allowedRoles": list(meta.allowed_roles),
            "version": 1,
            "status": "draft",
            "indexedVersion": None,
            "createdBy": actor,
            "createdAt": now,
            "updatedAt": now,
            **self._store(doc_id, 1, data, ctype),
        }
        self.db.kb_documents.insert_one(doc)
        return doc

    def replace(self, doc_id: str, data: bytes, declared_type: str, actor: str) -> Doc:
        if len(data) > parsers.MAX_BYTES:
            raise ShpError("file-too-large")
        ctype = sniff(data, declared_type)
        cur = self.get(doc_id)
        if cur["status"] == "retired":
            raise ShpError("invalid-state-transition")
        version = int(cur["version"]) + 1
        updated = self.db.kb_documents.find_one_and_update(
            {"_id": doc_id, "version": cur["version"]},
            {
                "$set": {
                    "version": version,
                    "status": "draft",
                    "updatedAt": self.clock(),
                    "updatedBy": actor,
                    **self._store(doc_id, version, data, ctype),
                },
                "$unset": {"approvedBy": "", "approvedAt": "", "indexError": ""},
            },
            return_document=ReturnDocument.AFTER,
        )
        if updated is None:
            raise ShpError("version-conflict")
        return updated

    def approve(self, doc_id: str, actor: str, actor_departments: tuple[str, ...], is_admin: bool) -> Doc:
        cur = self.get(doc_id)
        if not is_admin and (cur["department"] == HOSPITAL_WIDE or cur["department"] not in actor_departments):
            # a doctor may approve only their own department's documents; hospital-wide is admin-approved
            raise ShpError("forbidden")
        updated = self.db.kb_documents.find_one_and_update(
            {"_id": doc_id, "status": "draft", "version": cur["version"]},
            {"$set": {"status": "approved", "approvedBy": actor, "approvedAt": self.clock()}},
            return_document=ReturnDocument.AFTER,
        )
        if updated is None:
            raise ShpError("invalid-state-transition")
        return updated

    def index_document(self, doc_id: str) -> Doc:
        """Runs after approve (background thread). New chunks enter the index before old-version chunks are deleted:
        no coverage gap."""
        doc = self.get(doc_id)
        if doc["status"] != "approved":
            return doc
        last: Exception | None = None
        for _ in range(INDEX_ATTEMPTS):
            try:
                chunks = self._build_chunks(doc)
                self.index.upsert(chunks)
                self.index.delete_document(doc_id, except_version=int(doc["version"]))
                KB_INDEXED.labels(outcome="success").inc()
                out = self.db.kb_documents.find_one_and_update(
                    {"_id": doc_id, "version": doc["version"], "status": "approved"},
                    {
                        "$set": {
                            "status": "indexed",
                            "indexedVersion": doc["version"],
                            "chunkCount": len(chunks),
                            "indexedAt": self.clock(),
                            "embeddingModel": self.embedder.model,
                        },
                        "$unset": {"indexError": ""},
                    },
                    return_document=ReturnDocument.AFTER,
                )
                return out or self.get(doc_id)
            except parsers.ParseError as exc:
                last = exc
                break  # broken document: retrying is pointless
            except Exception as exc:  # noqa: BLE001 - transient index/embedding error
                last = exc
        KB_INDEXED.labels(outcome="failed").inc()
        reason = str(last) if isinstance(last, parsers.ParseError) else type(last).__name__
        self.db.kb_documents.update_one({"_id": doc_id}, {"$set": {"indexError": reason[:200]}})
        return self.get(doc_id)

    def retire(self, doc_id: str, actor: str) -> Doc:
        cur = self.get(doc_id)
        if cur["status"] == "retired":
            return cur
        self.index.delete_document(doc_id)
        out = self.db.kb_documents.find_one_and_update(
            {"_id": doc_id},
            {"$set": {"status": "retired", "retiredBy": actor, "retiredAt": self.clock(), "indexedVersion": None}},
            return_document=ReturnDocument.AFTER,
        )
        assert out is not None
        return out

    # ---------------- read ----------------
    def get(self, doc_id: str) -> Doc:
        doc = self.db.kb_documents.find_one({"_id": doc_id})
        if doc is None:
            raise ShpError("not-found")
        return doc

    def list_documents(self, departments: tuple[str, ...] | None, status: str | None = None) -> list[Doc]:
        q: Doc = {}
        if departments is not None:
            q["department"] = {"$in": [*departments, HOSPITAL_WIDE]}
        if status:
            q["status"] = status
        return list(self.db.kb_documents.find(q).sort("updatedAt", -1).limit(200))

    # ---------------- internal ----------------
    def _store(self, doc_id: str, version: int, data: bytes, ctype: str) -> Doc:
        sha = hashlib.sha256(data).hexdigest()
        path = f"{doc_id}/v{version}/{sha}"
        self.blobs.put(self.container, path, data, ctype)
        return {"blobPath": path, "contentType": ctype, "sha256": sha, "sizeBytes": len(data)}

    def _build_chunks(self, doc: Doc) -> list[IndexedChunk]:
        data = self.blobs.get(self.container, doc["blobPath"])
        if hashlib.sha256(data).hexdigest() != doc["sha256"]:
            raise parsers.ParseError("file in the store does not match the hash")
        pieces = chunk(parsers.parse(data, doc["contentType"]))
        # chunks containing AI-directed instructions never enter the index (prompt injection via documents, AI-17)
        flagged = [p for p in pieces if looks_like_instruction(p.text)]
        if flagged:
            KB_SUSPICIOUS.inc(len(flagged))
            self.db.kb_documents.update_one({"_id": doc["_id"]}, {"$set": {"suspiciousChunks": len(flagged)}})
            pieces = [p for p in pieces if p not in flagged]
        vectors = self.embedder.embed([f"{doc['title']}\n{p.section}\n{p.text}" for p in pieces])
        now = self.clock().isoformat()
        return [
            IndexedChunk(
                id=f"{doc['_id']}:{doc['version']}:{p.ordinal}",
                document_id=doc["_id"],
                document_version=int(doc["version"]),
                title=doc["title"],
                section=p.section,
                page=p.page,
                department=doc["department"],
                allowed_roles=tuple(doc["allowedRoles"]),
                content=p.text,
                vector=tuple(v),
                updated_at=now,
            )
            for p, v in zip(pieces, vectors, strict=True)
        ]
