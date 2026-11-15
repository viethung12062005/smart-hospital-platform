package org.shp.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventPublisher;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.support.MessageBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * Messaging contract with real RabbitMQ (13-events §1.1, SHR-08 criterion): a duplicate event is
 * processed only once; a failing handler is delivered 5 times then goes to the DLQ; a bad-schema
 * event goes to the DLQ without reaching the handler.
 */
@SpringBootTest(
    classes = MessagingRabbitTest.App.class,
    properties = {
      "spring.cloud.function.definition=testIn",
      "spring.cloud.stream.bindings.testIn-in-0.destination=shp-events",
      "spring.cloud.stream.bindings.testIn-in-0.group=test-svc",
      "spring.cloud.stream.rabbit.bindings.testIn-in-0.consumer.binding-routing-key="
          + MessagingRabbitTest.BOOKED,
      // short backoff for tests; the 5 attempts keep the default
      "spring.cloud.stream.default.consumer.back-off-initial-interval=10",
      "spring.cloud.stream.default.consumer.back-off-max-interval=50"
    })
@Testcontainers
class MessagingRabbitTest {
  static final String BOOKED = "shp.scheduling.appointment-booked.v1";
  static final String DLQ = "shp-events.test-svc.dlq";

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_RABBITMQ_IMAGE", "rabbitmq:4-management"))
              .asCompatibleSubstituteFor("rabbitmq"));

  @Autowired EventPublisher publisher;
  @Autowired StreamBridge bridge;
  @Autowired Recorder recorder;
  @Autowired InMemoryInbox inbox;
  @Autowired RabbitTemplate rabbit;

  static CloudEvent booked() throws Exception {
    JsonNode data =
        CloudEvents.JSON.readTree(
            Files.readString(
                Path.of(
                    "../../../contracts/events/scheduling/appointment-booked.v1.example.json")));
    return CloudEvents.create(
        "scheduling", BOOKED, "Appointment/" + java.util.UUID.randomUUID(), data);
  }

  @Test
  void publisherIsTheStreamImplementation() {
    assertThat(publisher).isInstanceOf(StreamEventPublisher.class);
  }

  @Test
  void duplicateDeliveryIsHandledOnce() throws Exception {
    CloudEvent evt = booked();
    publisher.publish(evt);
    publisher.publish(evt); // bus redelivers the same event (same id)

    await().atMost(Duration.ofSeconds(20)).until(() -> inbox.calls(evt.id()) == 2);
    assertThat(recorder.attempts(evt.id())).isEqualTo(1);
  }

  @Test
  void failingHandlerIsDeliveredFiveTimesThenDeadLettered() throws Exception {
    CloudEvent evt = booked();
    recorder.failFor(evt.id());
    publisher.publish(evt);

    Message dead =
        await().atMost(Duration.ofSeconds(30)).until(() -> rabbit.receive(DLQ), m -> m != null);
    assertThat(CloudEvents.parse(new String(dead.getBody(), StandardCharsets.UTF_8)).id())
        .isEqualTo(evt.id());
    assertThat(recorder.attempts(evt.id())).isEqualTo(5);
  }

  @Test
  void eventWithInvalidDataGoesToDlqWithoutReachingTheHandler() throws Exception {
    CloudEvent good = booked();
    CloudEvent bad =
        new CloudEvent(
            good.specVersion(),
            good.id(),
            good.source(),
            good.type(),
            good.subject(),
            good.time(),
            good.dataContentType(),
            good.dataSchema(),
            good.traceParent(),
            CloudEvents.JSON.readTree("{\"appointmentId\":\"not-a-uuid\"}"));
    bridge.send(
        StreamEventPublisher.BINDING,
        MessageBuilder.withPayload(CloudEvents.toJson(bad).getBytes(StandardCharsets.UTF_8))
            .setHeader(StreamEventPublisher.TYPE_HEADER, BOOKED)
            .build());

    Message dead =
        await().atMost(Duration.ofSeconds(30)).until(() -> rabbit.receive(DLQ), m -> m != null);
    assertThat(CloudEvents.parse(new String(dead.getBody(), StandardCharsets.UTF_8)).id())
        .isEqualTo(bad.id());
    assertThat(recorder.attempts(bad.id())).isZero();
    assertThat(inbox.calls(bad.id())).isZero();
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    @Bean
    InMemoryInbox inbox() {
      return new InMemoryInbox();
    }

    @Bean
    Recorder recorder() {
      return new Recorder();
    }

    @Bean
    Consumer<org.springframework.messaging.Message<byte[]>> testIn(
        InMemoryInbox inbox, Recorder recorder) {
      return EventConsumers.idempotent("test-svc", Set.of(BOOKED), inbox, recorder::handle);
    }
  }

  /**
   * Like PostgresInbox: record the marker then run the handler; on handler failure remove the
   * marker (rollback).
   */
  static final class InMemoryInbox implements Inbox {
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    @Override
    public boolean handleOnce(String consumer, CloudEvent event, Runnable handler) {
      calls.computeIfAbsent(event.id(), k -> new AtomicInteger()).incrementAndGet();
      String key = consumer + "|" + event.id();
      if (!seen.add(key)) {
        return false;
      }
      try {
        handler.run();
        return true;
      } catch (RuntimeException e) {
        seen.remove(key);
        throw e;
      }
    }

    int calls(String id) {
      AtomicInteger n = calls.get(id);
      return n == null ? 0 : n.get();
    }
  }

  static final class Recorder {
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    void handle(CloudEvent evt) {
      attempts.computeIfAbsent(evt.id(), k -> new AtomicInteger()).incrementAndGet();
      if (failing.contains(evt.id())) {
        throw new IllegalStateException("simulated failure");
      }
    }

    void failFor(String id) {
      failing.add(id);
    }

    int attempts(String id) {
      AtomicInteger n = attempts.get(id);
      return n == null ? 0 : n.get();
    }
  }
}
