"""Standard logging for Python services (NFR-O4, NFR-SEC13): trace-id in logs, PII masked.

Mirrors Java ``shp-service-defaults/logback-spring.xml`` + ``PiiMaskingConverter``.
The formatter wraps ``logging.Formatter`` to inject the current trace-id (from the active span) and
mask PII in the message before it is written. Services call ``configure_logging()`` at startup.
"""

import logging

from .pii import mask_pii
from .telemetry import current_trace_id

_FORMAT = "%(asctime)s %(levelname)s [%(name)s] [%(trace_id)s] %(message)s"


class PiiMaskingFormatter(logging.Formatter):
    """Masks PII in the message and injects the current trace-id."""

    def format(self, record: logging.LogRecord) -> str:
        # Mask the final message (after args interpolation), which is what actually gets written.
        record.msg = mask_pii(record.getMessage())
        record.args = ()
        record.trace_id = current_trace_id() or "-"
        return super().format(record)


def configure_logging(level: int = logging.INFO) -> None:
    """Attach the PII-masking, trace-id formatter to the root handler; replace old handlers."""
    handler = logging.StreamHandler()
    handler.setFormatter(PiiMaskingFormatter(_FORMAT))
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level)
