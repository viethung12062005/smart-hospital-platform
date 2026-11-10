"""Service ai (12): kho tri thức, trợ lý RAG, tóm tắt hồ sơ, trích xuất tài liệu, chatbot đặt lịch, usage."""

import logging
import threading
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from shp_ai import api_kb
from shp_ai.deps import Deps
from shp_ai.settings import Settings
from shp_platform.errors import ShpError, install_error_handlers
from shp_platform.security import Authenticator, AuthSettings
from shp_platform.telemetry import install_tracing

LOG = logging.getLogger("shp.ai")
Registrar = Callable[[FastAPI, Callable[[], Deps], Authenticator], None]
EXTRA_ROUTERS: list[Registrar] = []  # các module tính năng tự đăng ký (assistant, summary, extraction, booking, usage)


def build_app(
    deps_factory: Callable[[], Deps],
    auth: Authenticator,
    on_start: Callable[[Deps, threading.Event], None] | None = None,
) -> FastAPI:
    box: dict[str, Deps] = {}

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        box["deps"] = deps_factory()
        stop = threading.Event()
        if on_start is not None:
            on_start(box["deps"], stop)
        yield
        stop.set()

    app = FastAPI(title="shp-ai", version="0.1.0", lifespan=lifespan)
    install_error_handlers(app)
    install_tracing(app, "ai")

    def deps() -> Deps:
        return box["deps"]

    @app.get("/health/live")
    def live() -> dict[str, str]:
        return {"status": "Healthy"}

    @app.get("/health/ready")
    def ready() -> dict[str, str]:
        d = deps()
        if d.client is not None:
            try:
                d.client.admin.command("ping")
            except Exception as exc:  # noqa: BLE001
                raise ShpError("dependency-unavailable") from exc
        return {"status": "Healthy"}

    @app.get("/info")
    def info() -> dict[str, str]:
        s = deps().settings
        return {"name": s.service_name, "version": s.version, "gitSha": s.git_sha}

    @app.get("/metrics")
    def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    app.include_router(api_kb.router(deps, auth))
    for register in EXTRA_ROUTERS:
        register(app, deps, auth)
    return app


def _default_deps() -> Deps:  # pragma: no cover - dựng hạ tầng thật
    from shp_ai import wiring

    return wiring.from_settings(Settings())


def _start(deps: Deps, stop: threading.Event) -> None:  # pragma: no cover - hạ tầng thật
    from shp_ai import wiring

    wiring.start_background(deps, stop)


def _create() -> Any:
    from shp_ai import features  # noqa: F401 - đăng ký các router tính năng vào EXTRA_ROUTERS

    settings = Settings()
    if not settings.mongodb_uri:  # chạy không hạ tầng (test khói, CI): chỉ endpoint nền tảng
        from shp_ai.wiring import offline_deps

        return build_app(lambda: offline_deps(settings), Authenticator(AuthSettings.from_env()))
    return build_app(_default_deps, Authenticator(AuthSettings.from_env()), _start)


app = _create()
