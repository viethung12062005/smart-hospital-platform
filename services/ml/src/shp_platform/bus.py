"""Bus sự kiện cho service Python (PY-03; 13-events §1).

- Local: RabbitMQ topic exchange `shp-events`, routing key = `type`. Mỗi consumer một quorum queue
  `<consumer>`, `x-delivery-limit` = 4 (tổng 5 lần giao); vượt giới hạn hoặc lỗi vĩnh viễn → dead-letter exchange
  `shp-events.dlx` → queue `<consumer>.dlq`.
- Cloud: Azure Service Bus, topic `shp-events`, subscription = tên consumer (filter theo `type` do Terraform tạo);
  retry bằng abandon, quá 5 lần → dead-letter của subscription. Xác thực bằng workload identity (không có secret).
Quyết định xử lý (xong, trùng, thử lại, DLQ) nằm ở `shp_platform.events.consume`; module này chỉ chuyển giao.
"""

import json
import logging
import threading
from collections.abc import Callable, Sequence
from typing import Any, Protocol

from shp_platform.events import MAX_ATTEMPTS, Outcome

EXCHANGE = "shp-events"
DLX = "shp-events.dlx"
TOPIC = "shp-events"
CONTENT_TYPE = "application/cloudevents+json"
LOG = logging.getLogger("shp.bus")

Handler = Callable[[bytes, int], Outcome]  # (thân message, lần giao thứ mấy từ 1) → quyết định


class Bus(Protocol):
    def publish(self, event: dict[str, Any]) -> None: ...

    def consume(self, consumer: str, types: Sequence[str], handler: Handler, stop: threading.Event) -> None: ...


def _body(event: dict[str, Any]) -> bytes:
    return json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode()


class RabbitBus:
    def __init__(self, url: str) -> None:
        import pika

        self._pika = pika
        self._params = pika.URLParameters(url)
        self._lock = threading.Lock()
        self._conn: Any = None
        self._channel: Any = None

    def _declare(self, ch: Any) -> None:
        ch.exchange_declare(EXCHANGE, exchange_type="topic", durable=True)
        ch.exchange_declare(DLX, exchange_type="direct", durable=True)

    def publish(self, event: dict[str, Any]) -> None:
        with self._lock:
            if self._channel is None or self._channel.is_closed:
                self._conn = self._pika.BlockingConnection(self._params)
                self._channel = self._conn.channel()
                self._channel.confirm_delivery()
                self._declare(self._channel)
            self._channel.basic_publish(
                EXCHANGE,
                event["type"],
                _body(event),
                self._pika.BasicProperties(
                    content_type=CONTENT_TYPE, message_id=event["id"], delivery_mode=2, type=event["type"]
                ),
                mandatory=False,
            )

    def consume(self, consumer: str, types: Sequence[str], handler: Handler, stop: threading.Event) -> None:
        conn = self._pika.BlockingConnection(self._params)
        ch = conn.channel()
        self._declare(ch)
        ch.queue_declare(f"{consumer}.dlq", durable=True, arguments={"x-queue-type": "quorum"})
        ch.queue_bind(f"{consumer}.dlq", DLX, routing_key=consumer)
        ch.queue_declare(
            consumer,
            durable=True,
            arguments={
                "x-queue-type": "quorum",
                "x-delivery-limit": MAX_ATTEMPTS - 1,  # số lần giao lại, tổng = MAX_ATTEMPTS
                "x-dead-letter-exchange": DLX,
                "x-dead-letter-routing-key": consumer,
            },
        )
        for t in types:
            ch.queue_bind(consumer, EXCHANGE, routing_key=t)
        ch.basic_qos(prefetch_count=10)
        try:
            while not stop.is_set():
                method, props, body = ch.basic_get(consumer, auto_ack=False)
                if method is None:
                    conn.sleep(0.5)
                    continue
                headers = (props.headers or {}) if props else {}
                attempt = int(headers.get("x-delivery-count", 0)) + 1
                outcome = handler(body, attempt)
                if outcome in (Outcome.PROCESSED, Outcome.DUPLICATE):
                    ch.basic_ack(method.delivery_tag)
                elif outcome is Outcome.RETRY:
                    ch.basic_nack(method.delivery_tag, requeue=True)
                else:
                    ch.basic_nack(method.delivery_tag, requeue=False)  # → DLX → <consumer>.dlq
        finally:
            conn.close()

    def close(self) -> None:
        with self._lock:
            if self._conn is not None and self._conn.is_open:
                self._conn.close()


class ServiceBus:  # pragma: no cover - cần Azure Service Bus; thử ở UAT (IAC dữ liệu/hàng đợi)
    def __init__(self, namespace: str) -> None:
        from azure.identity import DefaultAzureCredential
        from azure.servicebus import ServiceBusClient

        self._client = ServiceBusClient(namespace, credential=DefaultAzureCredential())
        self._sender = self._client.get_topic_sender(TOPIC)
        self._lock = threading.Lock()

    def publish(self, event: dict[str, Any]) -> None:
        from azure.servicebus import ServiceBusMessage

        msg = ServiceBusMessage(
            _body(event),
            content_type=CONTENT_TYPE,
            message_id=event["id"],
            subject=event["type"],
            application_properties={"type": event["type"]},  # filter của subscription theo `type`
        )
        with self._lock:
            self._sender.send_messages(msg)

    def consume(self, consumer: str, types: Sequence[str], handler: Handler, stop: threading.Event) -> None:
        with self._client.get_subscription_receiver(TOPIC, consumer, max_wait_time=5) as receiver:
            while not stop.is_set():
                for msg in receiver.receive_messages(max_message_count=10, max_wait_time=5):
                    outcome = handler(b"".join(msg.body), int(msg.delivery_count or 0) + 1)
                    if outcome in (Outcome.PROCESSED, Outcome.DUPLICATE):
                        receiver.complete_message(msg)
                    elif outcome is Outcome.RETRY:
                        receiver.abandon_message(msg)
                    else:
                        receiver.dead_letter_message(msg, reason="shp-consumer", error_description=outcome.value)


def start_consumer(
    bus: Bus, consumer: str, types: Sequence[str], handler: Handler, stop: threading.Event
) -> threading.Thread:
    """Chạy consumer ở thread nền; lỗi kết nối thì ghi log (không có nội dung message) và thử lại sau 5 giây."""

    def loop() -> None:
        while not stop.is_set():
            try:
                bus.consume(consumer, types, handler, stop)
            except Exception as exc:  # noqa: BLE001
                LOG.warning("consumer %s mất kết nối bus: %s", consumer, type(exc).__name__)
                stop.wait(5)

    thread = threading.Thread(target=loop, name=f"consumer-{consumer}", daemon=True)
    thread.start()
    return thread
