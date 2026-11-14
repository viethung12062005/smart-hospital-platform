"""LOC-04: Python services continue the trace from traceparent and do not export OTLP when disabled."""

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from shp_platform import telemetry

TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
PARENT_SPAN = "00f067aa0ba902b7"


def _app(exporter: InMemorySpanExporter) -> FastAPI:
    app = FastAPI()

    @app.get("/api/v1/thing")
    def thing() -> dict[str, str | None]:
        return {"traceId": telemetry.current_trace_id()}

    @app.get("/health/live")
    def live() -> dict[str, str]:
        return {"status": "Healthy"}

    telemetry.install_tracing(app, "test-svc", processors=[SimpleSpanProcessor(exporter)])
    return app


def test_incoming_traceparent_is_continued() -> None:
    exporter = InMemorySpanExporter()
    with TestClient(_app(exporter)) as client:
        res = client.get("/api/v1/thing", headers={"traceparent": f"00-{TRACE_ID}-{PARENT_SPAN}-01"})
    assert res.json() == {"traceId": TRACE_ID}
    spans = exporter.get_finished_spans()
    server = [s for s in spans if s.parent is not None and f"{s.parent.span_id:016x}" == PARENT_SPAN]
    assert server, "the request span must be a child of the client span"
    assert f"{server[0].context.trace_id:032x}" == TRACE_ID
    assert server[0].resource.attributes["service.name"] == "test-svc"


def test_trace_is_started_when_client_sends_none() -> None:
    exporter = InMemorySpanExporter()
    with TestClient(_app(exporter)) as client:
        trace_id = client.get("/api/v1/thing").json()["traceId"]
    assert trace_id is not None and len(trace_id) == 32 and trace_id != "0" * 32


def test_probe_endpoints_create_no_span() -> None:
    exporter = InMemorySpanExporter()
    with TestClient(_app(exporter)) as client:
        assert client.get("/health/live").status_code == 200
    assert exporter.get_finished_spans() == ()


def test_no_trace_id_outside_a_request() -> None:
    assert telemetry.current_trace_id() is None


@pytest.mark.parametrize(("value", "expected"), [("true", True), ("TRUE", True), ("false", False), ("", False)])
def test_otlp_export_follows_the_shared_switch(monkeypatch: pytest.MonkeyPatch, value: str, expected: bool) -> None:
    monkeypatch.setenv("SHP_OTLP_EXPORT", value)
    assert telemetry.otlp_export_enabled() is expected


def test_otlp_exporter_is_added_only_when_enabled(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SHP_OTLP_EXPORT", "false")
    off = telemetry.install_tracing(FastAPI(), "svc")
    monkeypatch.setenv("SHP_OTLP_EXPORT", "true")
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector.test:4318/")
    on = telemetry.install_tracing(FastAPI(), "svc")
    try:
        assert len(off._active_span_processor._span_processors) == 0  # noqa: SLF001
        assert len(on._active_span_processor._span_processors) == 1  # noqa: SLF001
    finally:
        on.shutdown()
