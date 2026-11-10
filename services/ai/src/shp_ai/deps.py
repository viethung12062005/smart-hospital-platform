"""Runtime dependencies of the ai service, built once at startup; tests replace them with fakes."""

from dataclasses import dataclass, field
from typing import Any

from fastapi import Request

from shp_ai.blobstore import BlobStore
from shp_ai.directory import Directory
from shp_ai.kb.embedding import Embedder
from shp_ai.kb.index import SearchIndex
from shp_ai.kb.service import KnowledgeBase
from shp_ai.settings import Settings
from shp_platform import audit, mongo
from shp_platform.llm import Router
from shp_platform.security import Principal


@dataclass
class Deps:
    settings: Settings
    client: Any  # MongoClient (tz_aware)
    blobs: BlobStore
    index: SearchIndex
    embedder: Embedder
    directory: Directory
    router: Router
    extras: dict[str, Any] = field(default_factory=dict)  # client clinical/scheduling, token exchange...

    @property
    def db(self) -> Any:
        return self.client[self.settings.mongodb_db]

    @property
    def kb(self) -> KnowledgeBase:
        return KnowledgeBase(self.db, self.blobs, self.index, self.embedder, self.settings.kb_container)


def primary_role(p: Principal, preferred: tuple[str, ...] = ("doctor", "nurse", "admin", "dpo", "patient")) -> str:
    for r in preferred:
        if r in p.roles:
            return r
    return "system"


def record_audit(
    deps: Deps,
    principal: Principal,
    request: Request | None,
    *,
    action: str,
    resource: str,
    outcome: str = "success",
    patient_id: str | None = None,
    purpose: str | None = None,
    reason: str | None = None,
) -> None:
    """Audit E11 via the outbox (the relay publishes to the bus). Never contains questions, prompts or answers."""
    import uuid

    rec = audit.build(
        service="ai",
        action=action,
        outcome=outcome,
        actor_subject=principal.subject,
        actor_role=primary_role(principal),
        resource=resource,
        patient_id=uuid.UUID(patient_id) if patient_id else None,
        purpose_of_use=purpose,
        reason=reason,
        trace_id=request.headers.get("x-request-id") if request else None,
        client_ip=request.client.host if request and request.client else None,
        user_agent=request.headers.get("user-agent") if request else None,
    )
    db = deps.db
    mongo.in_transaction(deps.client, lambda s: mongo.add_to_outbox(db, s, audit.to_event(rec)))
