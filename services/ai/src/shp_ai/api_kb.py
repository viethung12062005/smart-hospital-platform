"""AI9: quản lý kho tri thức (12 §6).

Admin tải lên, thay file, gỡ; bác sĩ duyệt tài liệu khoa mình; admin duyệt mọi khoa.
"""

import threading
from collections.abc import Callable
from typing import Annotated, Any

from fastapi import APIRouter, Depends, File, Form, Request, UploadFile

from shp_ai.deps import Deps, record_audit
from shp_ai.kb.parsers import MAX_BYTES
from shp_ai.kb.service import NewDocument
from shp_platform.errors import ShpError
from shp_platform.security import Authenticator, Principal

SPECIALTY_PATTERN = r"^[a-z][a-z-]{1,40}$"


def public(doc: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "title",
        "source",
        "department",
        "allowedRoles",
        "version",
        "status",
        "indexedVersion",
        "contentType",
        "sizeBytes",
        "chunkCount",
        "indexError",
        "embeddingModel",
    )
    out = {"id": doc["_id"], **{k: doc.get(k) for k in keys}}
    for k in ("createdAt", "updatedAt", "approvedAt", "indexedAt"):
        if doc.get(k):
            out[k] = doc[k].isoformat()
    return out


async def _read_limited(file: UploadFile) -> bytes:
    data = await file.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ShpError("file-too-large")
    return data


def router(deps: Callable[[], Deps], auth: Authenticator) -> APIRouter:
    r = APIRouter(prefix="/api/v1/ai/kb")
    admin = auth.require_roles("admin")
    reviewer = auth.require_roles("doctor", "admin")

    def index_in_background(doc_id: str) -> None:
        d = deps()
        threading.Thread(target=d.kb.index_document, args=(doc_id,), name=f"kb-index-{doc_id}", daemon=True).start()

    @r.get("/documents")
    def list_documents(p: Annotated[Principal, Depends(reviewer)], status: str | None = None) -> dict[str, Any]:
        d = deps()
        scope = None if "admin" in p.roles else d.directory.departments(p)
        return {"items": [public(x) for x in d.kb.list_documents(scope, status)]}

    @r.post("/documents", status_code=201)
    async def upload(
        request: Request,
        p: Annotated[Principal, Depends(admin)],
        file: Annotated[UploadFile, File()],
        title: Annotated[str, Form(min_length=3, max_length=200)],
        source: Annotated[str, Form(min_length=3, max_length=300)],
        department: Annotated[str, Form(pattern=SPECIALTY_PATTERN)],
        allowedRoles: Annotated[list[str], Form()],  # noqa: N803 - tên trường theo hợp đồng API
    ) -> dict[str, Any]:
        d = deps()
        data = await _read_limited(file)
        doc = d.kb.upload(
            NewDocument(title, source, department, tuple(allowedRoles)), data, file.content_type or "", p.subject
        )
        record_audit(d, p, request, action="create", resource=f"KnowledgeDocument/{doc['_id']}")
        return public(doc)

    @r.put("/documents/{doc_id}")
    async def replace(
        request: Request, doc_id: str, p: Annotated[Principal, Depends(admin)], file: Annotated[UploadFile, File()]
    ) -> dict[str, Any]:
        d = deps()
        doc = d.kb.replace(doc_id, await _read_limited(file), file.content_type or "", p.subject)
        record_audit(d, p, request, action="update", resource=f"KnowledgeDocument/{doc_id}")
        return public(doc)

    @r.post("/documents/{doc_id}/approve")
    def approve(request: Request, doc_id: str, p: Annotated[Principal, Depends(reviewer)]) -> dict[str, Any]:
        d = deps()
        is_admin = "admin" in p.roles
        doc = d.kb.approve(doc_id, p.subject, () if is_admin else d.directory.departments(p), is_admin)
        record_audit(d, p, request, action="update", resource=f"KnowledgeDocument/{doc_id}", reason="approve")
        index_in_background(doc_id)
        return public(doc)

    @r.post("/documents/{doc_id}/retire")
    def retire(request: Request, doc_id: str, p: Annotated[Principal, Depends(admin)]) -> dict[str, Any]:
        d = deps()
        doc = d.kb.retire(doc_id, p.subject)
        record_audit(d, p, request, action="delete", resource=f"KnowledgeDocument/{doc_id}", reason="retire")
        return public(doc)

    return r
