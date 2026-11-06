package org.shp.messaging;

import com.azure.spring.messaging.servicebus.support.ServiceBusMessageHeaders;
import java.nio.charset.StandardCharsets;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventPublisher;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Phát CloudEvent lên topic {@code shp-events} qua Spring Cloud Stream (13-events §1.1). Header
 * {@code type} dùng cho bộ lọc subscription (Service Bus) và routing key (RabbitMQ); Service Bus
 * dùng {@code id} làm MessageId (chống trùng) và {@code subject} làm SessionId (giữ thứ tự).
 */
public final class StreamEventPublisher implements EventPublisher {
  /** Binding phát; destination đặt trong {@code META-INF/shp/messaging.properties}. */
  public static final String BINDING = "shp-events-out-0";

  public static final String TYPE_HEADER = "type";

  /**
   * Thân vẫn là CloudEvent đầy đủ (structured) nhưng khai báo {@code application/json}: với {@code
   * application/cloudevents+json}, Spring Cloud Function tự tách envelope ra header.
   */
  static final String CONTENT_TYPE = "application/json";

  private final StreamBridge bridge;

  public StreamEventPublisher(StreamBridge bridge) {
    this.bridge = bridge;
  }

  @Override
  public void publish(CloudEvent event) {
    if (!bridge.send(BINDING, toMessage(event))) {
      throw new IllegalStateException("Bus không nhận event " + event.id());
    }
  }

  static Message<byte[]> toMessage(CloudEvent event) {
    String session = event.subject() != null ? event.subject() : event.id();
    return MessageBuilder.withPayload(CloudEvents.toJson(event).getBytes(StandardCharsets.UTF_8))
        .setHeader(MessageHeaders.CONTENT_TYPE, CONTENT_TYPE)
        .setHeader(TYPE_HEADER, event.type())
        .setHeader(ServiceBusMessageHeaders.MESSAGE_ID, event.id())
        .setHeader(ServiceBusMessageHeaders.SESSION_ID, session)
        .build();
  }
}
