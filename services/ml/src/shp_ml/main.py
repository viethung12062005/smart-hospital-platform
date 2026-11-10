"""Service ml: consumer event lịch hẹn, chấm điểm no-show, API quan sát (11 §3), metric Prometheus.

M1 GET /api/v1/ml/predictions?appointment=  (nurse, admin; có audit)
M2 GET /api/v1/ml/model                     (admin)
M3 POST /internal/v1/ml/predict             (token dịch vụ: scheduling, CI)
"""

import logging
import threading
import uuid
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Query, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, Field
from pymongo import DESCENDING, MongoClient

from shp_ml.consumer import TYPES, NoShowConsumer, iso
from shp_ml.features import FEATURES
from shp_ml.monitor import start_daily
from shp_ml.registry import FileRegistry, MlflowRegistry, Registry
from shp_ml.serving import ModelHolder, start_poller
from shp_ml.settings import Settings
from shp_ml.store import ensure_schema
from shp_platform import audit, events, mongo
from shp_platform.bus import Bus, RabbitBus, ServiceBus, start_consumer
from shp_platform.errors import ShpError, install_error_handlers
from shp_platform.security import Authenticator, AuthSettings, Principal
from shp_platform.telemetry import install_tracing

LOG = logging.getLogger("shp.ml")


class State:
    """Phụ thuộc runtime; test thay bằng bản giả qua `build_app`."""

    def __init__(self, settings: Settings, client: Any, holder: ModelHolder, registry: Registry | None) -> None:
        self.settings = settings
        self.client = client
        self.db = client[settings.mongodb_db] if client is not None else None
        self.holder = holder
        self.registry = registry


def make_registry(s: Settings) -> Registry | None:
    if s.mlflow_tracking_uri:
        return MlflowRegistry(s.mlflow_tracking_uri)
    if s.registry_dir:
        from pathlib import Path

        return FileRegistry(Path(s.registry_dir))
    return None


def make_bus(s: Settings) -> Bus | None:
    if s.servicebus_namespace:
        return ServiceBus(s.servicebus_namespace)
    if s.rabbitmq_url:
        return RabbitBus(s.rabbitmq_url)
    return None


class PredictRequest(BaseModel):
    lead_time_days: float = Field(ge=0, le=366)
    prior_booked: int = Field(ge=0)
    prior_noshow: int = Field(ge=0)
    prior_late_cancel: int = Field(ge=0)
    noshow_rate_smoothed: float = Field(ge=0, le=1)
    days_since_last_noshow: int = Field(ge=0)
    is_first_visit: int = Field(ge=0, le=1)
    start_hour: str = Field(pattern=r"^\d{1,2}$")
    weekday: str = Field(pattern=r"^[1-7]$")
    specialty: str = Field(min_length=1, max_length=64)
    channel: str = Field(pattern=r"^(web|chatbot|staff)$")
    age_band: str = Field(pattern=r"^(\d{1,3}-\d{1,3}|unknown)$")


def build_app(
    state_factory: Callable[[], State],
    auth: Authenticator,
    predict_callers: list[str],
    start_background: bool = True,
) -> FastAPI:
    holder_box: dict[str, State] = {}

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        st = state_factory()
        holder_box["state"] = st
        stop = threading.Event()
        if start_background and st.db is not None:
            ensure_schema(st.db)
            st.holder.refresh()
            start_poller(st.holder, st.settings.model_poll_seconds, stop)
            start_daily(st.db, st.holder, stop)  # ML-10: 02:00 giờ VN
            bus = make_bus(st.settings)
            if bus is not None:
                consumer = NoShowConsumer(st.client, st.db, st.holder, st.settings.consumer_name)
                start_consumer(
                    bus,
                    st.settings.consumer_name,
                    TYPES,
                    lambda raw, n: (
                        events.consume(raw, st.settings.consumer_name, consumer.handle, _NoInbox(), n).outcome
                    ),
                    stop,
                )
                _start_relay(st, bus, stop)
        yield
        stop.set()

    app = FastAPI(title="shp-ml", version="0.1.0", lifespan=lifespan)
    install_error_handlers(app)
    install_tracing(app, "ml")

    def state() -> State:
        return holder_box["state"]

    @app.get("/health/live")
    def live() -> dict[str, str]:
        return {"status": "Healthy"}

    @app.get("/health/ready")
    def ready() -> dict[str, str]:
        st = state()
        if st.client is not None:
            try:
                st.client.admin.command("ping")
            except Exception as exc:  # noqa: BLE001
                raise ShpError("dependency-unavailable") from exc
        return {"status": "Healthy"}

    @app.get("/info")
    def info() -> dict[str, str]:
        s = state().settings
        return {"name": s.service_name, "version": s.version, "gitSha": s.git_sha}

    @app.get("/metrics")
    def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @app.get("/api/v1/ml/predictions")
    def get_prediction(
        request: Request,
        principal: Annotated[Principal, Depends(auth.require_roles("nurse", "admin"))],
        appointment: Annotated[uuid.UUID, Query()],
    ) -> dict[str, Any]:
        st = state()
        if st.db is None:
            raise ShpError("dependency-unavailable")
        doc = st.db.predictions.find_one(
            {"appointmentId": str(appointment), "shadow": {"$ne": True}}, sort=[("predictedAt", DESCENDING)]
        )
        _audit(
            st,
            principal,
            request,
            f"Appointment/{appointment}",
            doc["patientId"] if doc else None,
            "success" if doc else "error",
        )
        if doc is None:
            raise ShpError("not-found")
        f = doc.get("features", {})
        return {
            "appointmentId": doc["appointmentId"],
            "score": doc["score"],
            "threshold": doc["threshold"],
            "highRisk": doc["highRisk"],
            "modelName": doc["modelName"],
            "modelVersion": doc["modelVersion"],
            "predictedAt": iso(doc["predictedAt"]),
            "features": {k: f.get(k) for k in FEATURES if k in f},
        }

    @app.get("/api/v1/ml/model")
    def get_model(_: Annotated[Principal, Depends(auth.require_roles("admin"))]) -> dict[str, Any]:
        prod = state().holder.production()
        if prod is None:
            raise ShpError("not-found")
        model, meta = prod
        m = meta.get("metrics", {})
        cand = state().holder.candidate()
        return {
            "modelName": model.name,
            "modelVersion": model.version,
            "threshold": model.threshold,
            "registeredAt": meta.get("registeredAt"),
            "datasetSha256": meta.get("datasetSha256"),
            "metrics": {k: m.get(k) for k in ("auc", "baselineAuc", "ece", "latencyP95Ms", "positiveRate")},
            "shadowCandidateVersion": cand.version if cand else None,
        }

    @app.post("/internal/v1/ml/predict")
    def predict(
        body: PredictRequest,
        _: Annotated[Principal, Depends(auth.require_service("ml", *predict_callers))],
    ) -> dict[str, Any]:
        result = state().holder.score(body.model_dump())
        if result is None:
            raise ShpError("dependency-unavailable")
        return {"score": result.score, "highRisk": result.high_risk, "modelVersion": result.model_version}

    return app


class _NoInbox:
    """Inbox thật nằm trong transaction của NoShowConsumer (mongo.handle_once); lớp ngoài không ghi lần nữa."""

    def seen(self, consumer: str, event_id: str) -> bool:
        return False

    def mark(self, consumer: str, event_id: str) -> None:
        return None


def _audit(st: State, p: Principal, request: Request, resource: str, patient_id: str | None, outcome: str) -> None:
    if st.client is None or st.db is None:
        return
    rec = audit.build(
        service="ml",
        action="read",
        outcome=outcome,
        actor_subject=p.subject,
        actor_role=sorted(p.roles)[0] if p.roles else "system",
        resource=resource,
        patient_id=uuid.UUID(patient_id) if patient_id else None,
        purpose_of_use="HOPERAT",
        trace_id=request.headers.get("x-request-id"),
        client_ip=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )
    db = st.db
    mongo.in_transaction(st.client, lambda s: mongo.add_to_outbox(db, s, audit.to_event(rec)))


def _start_relay(st: State, bus: Bus, stop: threading.Event) -> None:
    db = st.db
    assert db is not None

    def loop() -> None:
        while not stop.wait(1.0):
            try:
                mongo.relay_once(db, bus)
            except Exception as exc:  # noqa: BLE001 - bus lỗi: thử lại lượt sau, event vẫn trong outbox
                LOG.warning("relay outbox lỗi: %s", type(exc).__name__)

    threading.Thread(target=loop, name="outbox-relay", daemon=True).start()


def _default_state() -> State:
    s = Settings()
    client: Any = MongoClient(s.mongodb_uri, tz_aware=True) if s.mongodb_uri else None
    registry = make_registry(s)
    return State(s, client, ModelHolder(registry, s.shadow_enabled), registry)


app = build_app(_default_state, Authenticator(AuthSettings.from_env()), Settings().callers())
