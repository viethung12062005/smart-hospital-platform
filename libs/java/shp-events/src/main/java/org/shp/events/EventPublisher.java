package org.shp.events;

/**
 * Đưa một CloudEvent lên bus. Cài đặt thật: Spring Cloud Stream (RabbitMQ local, Azure Service Bus
 * cloud); test dùng bản giả. Ném exception nếu bus từ chối — relay outbox sẽ ghi nhận và thử lại.
 */
@FunctionalInterface
public interface EventPublisher {
  void publish(CloudEvent event);
}
