"""OpenTelemetry tracing for FastAPI services (NFR-O1): continue the trace from the `traceparent` header, one span
per request.

Same behavior as Java services (shp-service-defaults): always create and propagate trace context; export OTLP only
when the cluster sets `SHP_OTLP_EXPORT=true` (endpoint from `OTEL_EXPORTER_OTLP_ENDPOINT`). Spans carry no
request/response content, so there is no personal data (NFR-O4).
"""

import os

from fastapi import FastAPI
from opentelemetry import trace
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import SpanProcessor, TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

# Platform endpoints called constantly (probes, scrapes): create no span.
EXCLUDED_URLS = "health/live,health/ready,metrics"


def otlp_export_enabled() -> bool:
    return os.environ.get("SHP_OTLP_EXPORT", "false").lower() == "true"


def install_tracing(
    app: FastAPI, service_name: str, *, processors: list[SpanProcessor] | None = None
) -> TracerProvider:
    """Attach tracing to `app`. `processors` are test-only (instead of OTLP)."""
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
    """Trace id (32 hex characters) of the request being processed, for logging; None when not inside a span."""
    ctx = trace.get_current_span().get_span_context()
    return f"{ctx.trace_id:032x}" if ctx.is_valid else None
