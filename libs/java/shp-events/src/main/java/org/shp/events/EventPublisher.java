package org.shp.events;

/**
 * Put a CloudEvent on the bus. Real implementation: Spring Cloud Stream (RabbitMQ locally, Azure
 * Service Bus in the cloud); tests use a fake. Throws if the bus rejects it — the outbox relay
 * records it and retries.
 */
@FunctionalInterface
public interface EventPublisher {
  void publish(CloudEvent event);
}
