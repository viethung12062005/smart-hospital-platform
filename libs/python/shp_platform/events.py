"""CloudEvents 1.0 for Python services (PY-03): create, validate the schema, process idempotently via the inbox,
retry then DLQ.

Matches org.shp.events.CloudEvents: UUIDv7 id, `source` = /services/<svc>, `dataschema` derived from the type,
≤ 64 KB.
Schemas live in shp_platform/schemas (tools/pylib/sync.py copies them from contracts/events). The broker wiring
(RabbitMQ locally, Service Bus in the cloud) lives in the service; the library only decides: process, skip
(duplicate) or move to the DLQ.
"""

import json
import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from functools import cache
from importlib import resources
from typing import Any, Protocol

from jsonschema import Draft202012Validator, FormatChecker

from shp_platform.ids import uuid7

MAX_BYTES = 64 * 1024
MAX_ATTEMPTS = 5  # after the 5th failure, move to the DLQ (13-events §1)
_TYPE = re.compile(r"^shp\.([a-z]+)\.([a-z-]+)\.v([0-9]+)$")


class EventError(ValueError):
    pass


@cache
def _schema(path: str) -> Draft202012Validator:
    text = resources.files("shp_platform").joinpath("schemas", path).read_text(encoding="utf-8")
    return Draft202012Validator(json.loads(text), format_checker=FormatChecker())


def _data_schema_path(event_type: str) -> str:
    m = _TYPE.match(event_type)
    if not m:
        raise EventError(f"type must be shp.<context>.<event>.v<N>: {event_type}")
    return f"{m.group(1)}/{m.group(2)}.v{m.group(3)}.json"


def create(
    service: str, event_type: str, subject: str, data: dict[str, Any], traceparent: str | None = None
) -> dict[str, Any]:
    if not service:
        raise EventError("service is required")
    m = _TYPE.match(event_type)
    if not m:
        raise EventError(f"type must be shp.<context>.<event>.v<N>: {event_type}")
    evt: dict[str, Any] = {
        "specversion": "1.0",
        "id": str(uuid7()),
        "source": f"/services/{service}",
        "type": event_type,
        "subject": subject,
        "time": datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "datacontenttype": "application/json",
        "dataschema": f"https://shp.example.org/schemas/{m.group(1)}/{m.group(2)}/v{m.group(3)}.json",
        "data": data,
    }
    if traceparent:
        evt["traceparent"] = traceparent
    validate(evt)
    return evt


def validate(evt: dict[str, Any]) -> None:
    """Validate the envelope and `data` against the type's schema. Error messages name only field paths, never
    values."""
    if len(json.dumps(evt, ensure_ascii=False).encode()) > MAX_BYTES:
        raise EventError(f"Event exceeds {MAX_BYTES} bytes")
    for validator, doc, where in (
        (_schema("envelope.json"), evt, "envelope"),
        (_schema(_data_schema_path(str(evt.get("type", "")))), evt.get("data"), "data"),
    ):
        errors = sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path))
        if errors:
            paths = ", ".join("/".join(str(p) for p in e.absolute_path) or "(root)" for e in errors[:5])
            raise EventError(f"{where} fails schema at: {paths}")


def parse(raw: bytes | str) -> dict[str, Any]:
    try:
        evt = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise EventError("not JSON") from exc
    if not isinstance(evt, dict):
        raise EventError("event must be an object")
    validate(evt)
    return evt


class Inbox(Protocol):
    """Record that an event was processed (key = consumer + event id). The Mongo/Postgres version lives in the same
    transaction as the business work."""

    def seen(self, consumer: str, event_id: str) -> bool: ...

    def mark(self, consumer: str, event_id: str) -> None: ...


class InMemoryInbox:
    def __init__(self) -> None:
        self._ids: set[tuple[str, str]] = set()

    def seen(self, consumer: str, event_id: str) -> bool:
        return (consumer, event_id) in self._ids

    def mark(self, consumer: str, event_id: str) -> None:
        self._ids.add((consumer, event_id))


class Outcome(Enum):
    PROCESSED = "processed"
    DUPLICATE = "duplicate"
    RETRY = "retry"
    DEAD_LETTER = "dead-letter"


@dataclass(frozen=True)
class Result:
    outcome: Outcome
    reason: str = ""  # contains no event data


class PermanentError(Exception):
    """A handler throws this when a retry is pointless (bad business data): move to the DLQ immediately."""


def consume(
    raw: bytes | str,
    consumer: str,
    handler: Callable[[dict[str, Any]], None],
    inbox: Inbox,
    delivery_count: int,
) -> Result:
    """Process one message. `delivery_count` starts at 1 (Service Bus DeliveryCount, RabbitMQ x-delivery-count + 1)."""
    try:
        evt = parse(raw)
    except EventError as exc:
        return Result(Outcome.DEAD_LETTER, str(exc))
    if inbox.seen(consumer, evt["id"]):
        return Result(Outcome.DUPLICATE)
    try:
        handler(evt)
    except PermanentError as exc:
        return Result(Outcome.DEAD_LETTER, type(exc).__name__)
    except Exception as exc:  # noqa: BLE001 - every transient error is retried
        if delivery_count >= MAX_ATTEMPTS:
            return Result(Outcome.DEAD_LETTER, type(exc).__name__)
        return Result(Outcome.RETRY, type(exc).__name__)
    inbox.mark(consumer, evt["id"])
    return Result(Outcome.PROCESSED)
