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
 * Publish CloudEvents to the {@code shp-events} topic via Spring Cloud Stream (13-events §1.1). The
 * {@code type} header is used for the subscription filter (Service Bus) and routing key (RabbitMQ);
 * Service Bus uses {@code id} as MessageId (deduplication) and {@code subject} as SessionId
 * (ordering).
 */
public final class StreamEventPublisher implements EventPublisher {
  /** Publishing binding; destination set in {@code META-INF/shp/messaging.properties}. */
  public static final String BINDING = "shp-events-out-0";

  public static final String TYPE_HEADER = "type";

  /**
   * The body is still a full (structured) CloudEvent but declares {@code application/json}: with
   * {@code application/cloudevents+json}, Spring Cloud Function unwraps the envelope into headers
   * automatically.
   */
  static final String CONTENT_TYPE = "application/json";

  private final StreamBridge bridge;

  public StreamEventPublisher(StreamBridge bridge) {
    this.bridge = bridge;
  }

  @Override
  public void publish(CloudEvent event) {
    if (!bridge.send(BINDING, toMessage(event))) {
      throw new IllegalStateException("Bus did not accept event " + event.id());
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
