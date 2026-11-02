"""CloudEvents 1.0 cho service Python (PY-03): tạo, kiểm lược đồ, xử lý idempotent qua inbox, retry rồi DLQ.

Giống org.shp.events.CloudEvents: id UUIDv7, `source` = /services/<svc>, `dataschema` suy từ type, ≤ 64 KB.
Lược đồ nằm trong shp_platform/schemas (tools/pylib/sync.py chép từ contracts/events). Phần nối broker (RabbitMQ local,
Service Bus cloud) nằm ở service; thư viện chỉ quyết định: xử lý, bỏ qua (trùng) hay chuyển DLQ.
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
MAX_ATTEMPTS = 5  # sau lần thứ 5 lỗi thì chuyển DLQ (13-events §1)
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
        raise EventError(f"type phải dạng shp.<context>.<event>.v<N>: {event_type}")
    return f"{m.group(1)}/{m.group(2)}.v{m.group(3)}.json"


def create(
    service: str, event_type: str, subject: str, data: dict[str, Any], traceparent: str | None = None
) -> dict[str, Any]:
    if not service:
        raise EventError("service bắt buộc")
    m = _TYPE.match(event_type)
    if not m:
        raise EventError(f"type phải dạng shp.<context>.<event>.v<N>: {event_type}")
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
    """Kiểm envelope và `data` theo lược đồ của type. Thông điệp lỗi chỉ nêu đường dẫn trường, không nêu giá trị."""
    if len(json.dumps(evt, ensure_ascii=False).encode()) > MAX_BYTES:
        raise EventError(f"Event vượt {MAX_BYTES} byte")
    for validator, doc, where in (
        (_schema("envelope.json"), evt, "envelope"),
        (_schema(_data_schema_path(str(evt.get("type", "")))), evt.get("data"), "data"),
    ):
        errors = sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path))
        if errors:
            paths = ", ".join("/".join(str(p) for p in e.absolute_path) or "(gốc)" for e in errors[:5])
            raise EventError(f"{where} sai lược đồ tại: {paths}")


def parse(raw: bytes | str) -> dict[str, Any]:
    try:
        evt = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise EventError("không phải JSON") from exc
    if not isinstance(evt, dict):
        raise EventError("event phải là object")
    validate(evt)
    return evt


class Inbox(Protocol):
    """Ghi nhận event đã xử lý (khóa = consumer + event id). Bản Mongo/Postgres nằm cùng transaction với nghiệp vụ."""

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
    reason: str = ""  # không chứa dữ liệu event


class PermanentError(Exception):
    """Handler ném lỗi này khi thử lại vô ích (dữ liệu sai nghiệp vụ): chuyển DLQ ngay."""


def consume(
    raw: bytes | str,
    consumer: str,
    handler: Callable[[dict[str, Any]], None],
    inbox: Inbox,
    delivery_count: int,
) -> Result:
    """Xử lý một message. `delivery_count` bắt đầu từ 1 (Service Bus DeliveryCount, RabbitMQ x-delivery-count + 1)."""
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
    except Exception as exc:  # noqa: BLE001 - mọi lỗi tạm thời đều thử lại
        if delivery_count >= MAX_ATTEMPTS:
            return Result(Outcome.DEAD_LETTER, type(exc).__name__)
        return Result(Outcome.RETRY, type(exc).__name__)
    inbox.mark(consumer, evt["id"])
    return Result(Outcome.PROCESSED)
