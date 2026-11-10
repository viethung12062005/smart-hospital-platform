"""Tracing OpenTelemetry cho service FastAPI (NFR-O1): nối trace từ header `traceparent`, mỗi request một span.

Cùng hành vi với service Java (shp-service-defaults): luôn tạo và truyền trace context; chỉ xuất OTLP khi cụm bật
`SHP_OTLP_EXPORT=true` (endpoint lấy từ `OTEL_EXPORTER_OTLP_ENDPOINT`). Span không mang nội dung request/response,
nên không có dữ liệu cá nhân (NFR-O4).
"""

import os

from fastapi import FastAPI
from opentelemetry import trace
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import SpanProcessor, TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

# Endpoint nền tảng bị gọi liên tục (probe, scrape): không tạo span.
EXCLUDED_URLS = "health/live,health/ready,metrics"


def otlp_export_enabled() -> bool:
    return os.environ.get("SHP_OTLP_EXPORT", "false").lower() == "true"


def install_tracing(
    app: FastAPI, service_name: str, *, processors: list[SpanProcessor] | None = None
) -> TracerProvider:
    """Gắn tracing cho `app`. `processors` chỉ dùng trong test (thay cho OTLP)."""
    provider = TracerProvider(resource=Resource.create({"service.name": service_name}))
    for processor in processors or []:
        provider.add_span_processor(processor)
    if processors is None and otlp_export_enabled():
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

        endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318").rstrip("/")
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=f"{endpoint}/v1/traces")))
    FastAPIInstrumentor.instrument_app(app, tracer_provider=provider, excluded_urls=EXCLUDED_URLS)
    return provider


def current_trace_id() -> str | None:
    """Trace id (32 ký tự hex) của request đang xử lý, để ghi log; None nếu không trong span."""
    ctx = trace.get_current_span().get_span_context()
    return f"{ctx.trace_id:032x}" if ctx.is_valid else None
