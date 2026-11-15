"""AI4 `POST /api/v1/ai/patients/{id}/summary` (AI-21; 12 §3).

Only a doctor with a care relationship or an active break-glass (403 `no-care-relationship`). Idempotency-Key required.
Cached 10 minutes per (patient, doctor, data version = hash of the records). Audit `ai-summary` (TREAT or ETREAT).
"""

import hashlib
import uuid
from collections.abc import Callable
from datetime import UTC, date, datetime, timedelta
from typing import Annotated, Any

from fastapi import APIRouter, Depends, FastAPI, Header, Request
from pydantic import BaseModel

from shp_ai import main, usage
from shp_ai.deps import Deps, record_audit
from shp_ai.llm_config import cost_usd
from shp_ai.summary.clinical import ClinicalReader, Evidence
from shp_ai.summary.service import AI_LABEL, TASK, TIMELINE_LABEL, summarize
from shp_platform.errors import ShpError
from shp_platform.idempotency import Idempotency, request_hash
from shp_platform.security import Authenticator, Principal

CACHE_TTL = timedelta(minutes=10)
DEFAULT_MONTHS = 24


class SummaryBody(BaseModel):
    scope: dict[str, str] | None = None  # {"from": "2025-11-01"}

    def since(self) -> date | None:
        raw = (self.scope or {}).get("from")
        if raw is None:
            return None
        try:
            return date.fromisoformat(raw)
        except ValueError as exc:
            raise ShpError("validation-failed", "scope.from phải là ngày YYYY-MM-DD.") from exc


def _data_version(ev: list[Evidence]) -> str:
    return hashlib.sha256("|".join(f"{e.ref}@{e.time}:{e.text}" for e in ev).encode()).hexdigest()


def ensure_indexes(db: Any) -> None:
    db.summary_cache.create_index("createdAt", expireAfterSeconds=int(CACHE_TTL.total_seconds()))


def register(app: FastAPI, deps: Callable[[], Deps], auth: Authenticator) -> None:
    r = APIRouter(prefix="/api/v1/ai")
    doctor = auth.require_roles("doctor")

    @r.post("/patients/{patient_id}/summary")
    def summary(
        request: Request,
        patient_id: uuid.UUID,
        p: Annotated[Principal, Depends(doctor)],
        idempotency_key: Annotated[str | None, Header()] = None,
        body: SummaryBody | None = None,
    ) -> dict[str, Any]:
        d = deps()
        if not idempotency_key:
            raise ShpError("idempotency-key-required")
        pid = str(patient_id)
        via = d.directory.care_via(p, pid)
        if via is None:
            record_audit(
                d,
                p,
                request,
                action="ai-summary",
                resource=f"Patient/{pid}",
                outcome="denied",
                patient_id=pid,
                purpose="TREAT",
            )
            raise ShpError("no-care-relationship")
        purpose = "ETREAT" if via == "break-glass" else "TREAT"
        since = (body.since() if body else None) or (datetime.now(UTC) - timedelta(days=30 * DEFAULT_MONTHS)).date()
        idem = Idempotency(d.db)
        scope_key = f"summary:{pid}"
        replay = idem.begin(p.subject, scope_key, idempotency_key, request_hash({"p": pid, "from": str(since)}))
        if replay is not None:
            return dict(replay["body"])
        try:
            exchanger = d.extras["exchanger"]
            reader = ClinicalReader(
                d.settings.clinical_url, lambda: exchanger.exchange(p.token, "clinical"), d.extras.get("clinical_http")
            )
            ev = reader.gather(pid, since)
            version = _data_version(ev)
            cache_id = hashlib.sha256(f"{pid}|{p.subject}|{version}".encode()).hexdigest()
            cached = d.db.summary_cache.find_one({"_id": cache_id})
            if cached is not None:
                out = dict(cached["response"])
            else:
                res = summarize(d.router, ev, reader.practitioner_names)
                cfg = d.extras["llm_config"]
                for a in res.attempts:
                    usage.record(d.db, TASK, a, cost_usd(cfg, a.provider, a.tokens_in, a.tokens_out), "doctor")
                out = {
                    "generatedAt": datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
                    "label": AI_LABEL if res.mode == "ai" else TIMELINE_LABEL,
                    "sections": res.sections,
                    "dataVersion": version[:16],
                    "droppedItems": res.dropped,
                }
                if res.mode != "ai":
                    out["mode"] = "structured-timeline"
                if res.mode == "ai" and res.router is not None:
                    out["model"] = {
                        "provider": res.router.provider,
                        "name": res.router.model,
                        "fallbackLevel": res.router.fallback_level,
                    }
                    out["promptVersion"] = res.prompt_version
                d.db.summary_cache.replace_one(
                    {"_id": cache_id}, {"_id": cache_id, "response": out, "createdAt": datetime.now(UTC)}, upsert=True
                )
        except Exception:
            idem.abandon(p.subject, scope_key, idempotency_key)
            raise
        idem.finish(p.subject, scope_key, idempotency_key, 200, out)
        reason = f"mode={out.get('mode', 'ai')} records={len(ev)} dropped={out['droppedItems']}"
        record_audit(
            d,
            p,
            request,
            action="ai-summary",
            resource=f"Patient/{pid}",
            patient_id=pid,
            purpose=purpose,
            reason=reason,
        )
        return out

    app.include_router(r)


main.EXTRA_ROUTERS.append(register)
