package org.shp.data.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventPublisher;
import org.shp.events.EventSchemas;
import org.shp.events.Uuids;

class OutboxTest extends PostgresTestBase {
  static final Instant T0 = Instant.parse("2026-11-20T02:00:00Z");
  final PostgresOutbox outbox = new PostgresOutbox(JDBC);

  static CloudEvent evt(int n) {
    return CloudEvents.create(
        "scheduling",
        "shp.scheduling.appointment-checked-in.v1",
        "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e%02d".formatted(n),
        Map.of(
            "appointmentId",
            Uuids.v7(),
            "version",
            n,
            "patientId",
            Uuids.v7(),
            "practitionerId",
            Uuids.v7(),
            "specialty",
            "cardiology",
            "checkedInAt",
            "2026-12-03T01:15:00Z"),
        Clock.fixed(T0.plusSeconds(n), ZoneOffset.UTC),
        null);
  }

  static final class Recording implements EventPublisher {
    final ConcurrentLinkedQueue<CloudEvent> published = new ConcurrentLinkedQueue<>();
    volatile Integer failOnVersion;
    volatile String failMessage = "bus down";
    volatile long delayMs;

    @Override
    public void publish(CloudEvent e) {
      if (failOnVersion != null && e.data().get("version").asInt() == failOnVersion) {
        throw new IllegalStateException(failMessage);
      }
      if (delayMs > 0) {
        try {
          Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
      published.add(e);
    }

    List<Integer> versions() {
      return published.stream().map(e -> e.data().get("version").asInt()).toList();
    }
  }

  OutboxRelay relay(EventPublisher p, int batch) {
    return new OutboxRelay(JDBC, TX, p, Clock.systemUTC(), batch, Duration.ofSeconds(1));
  }

  void add(CloudEvent... events) {
    TX.executeWithoutResult(s -> List.of(events).forEach(outbox::add));
  }

  @Test
  void dataChangeAndEventCommitOrRollBackTogether() {
    TX.executeWithoutResult(
        s -> {
          insertThing("a");
          outbox.add(evt(1));
          s.setRollbackOnly();
        });
    assertThat(count("SELECT count(*) FROM thing")).isZero();
    assertThat(count("SELECT count(*) FROM outbox")).isZero();
    add(evt(1));
    assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isEqualTo(1);
  }

  @Test
  void addOutsideATransactionIsRefused() {
    assertThatThrownBy(() -> outbox.add(evt(1))).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void relayPublishesInTimeOrderAndMarksPublished() {
    var pub = new Recording();
    add(evt(3), evt(1), evt(2));
    assertThat(relay(pub, 100).relayOnce()).isEqualTo(3);
    assertThat(pub.versions()).containsExactly(1, 2, 3);
    assertThat(relay(pub, 100).relayOnce()).isZero();
    assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL")).isEqualTo(3);
  }

  @Test
  void publishedEventIsTheSameCloudEventThatWasStored() {
    var pub = new Recording();
    var original = evt(1);
    add(original);
    relay(pub, 100).relayOnce();
    var got = pub.published.peek();
    assertThat(got).isEqualTo(original);
    assertThat(EventSchemas.validate(got)).isEmpty();
  }

  @Test
  void busOutageLosesNothingAndRecoversInOrder() {
    var pub = new Recording();
    pub.failOnVersion = 2;
    add(evt(1), evt(2), evt(3));
    assertThatThrownBy(() -> relay(pub, 100).relayOnce())
        .isInstanceOfSatisfying(
            OutboxRelay.OutboxPublishException.class,
            e -> assertThat(e.publishedBeforeFailure()).isEqualTo(1));
    assertThat(pub.versions()).containsExactly(1);
    assertThat(
            JDBC.sql("SELECT attempts FROM outbox WHERE subject LIKE '%e02'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(
            JDBC.sql("SELECT attempts FROM outbox WHERE subject LIKE '%e03'")
                .query(Integer.class)
                .single())
        .isZero();
    pub.failOnVersion = null; // bus sống lại
    assertThat(relay(pub, 100).relayOnce()).isEqualTo(2);
    assertThat(pub.versions()).containsExactly(1, 2, 3);
  }

  @Test
  void lastErrorNeverContainsThePayload() {
    var pub = new Recording();
    pub.failOnVersion = 1;
    pub.failMessage = "broker refused";
    add(evt(1));
    assertThatThrownBy(() -> relay(pub, 100).relayOnce())
        .isInstanceOf(OutboxRelay.OutboxPublishException.class);
    String err = JDBC.sql("SELECT last_error FROM outbox").query(String.class).single();
    assertThat(err).contains("broker refused").doesNotContain("patientId");
  }

  @Test
  void severalReplicasNeverPublishAnEventTwice() throws Exception {
    var pub = new Recording();
    pub.delayMs = 3;
    add(IntStream.rangeClosed(1, 60).mapToObj(OutboxTest::evt).toArray(CloudEvent[]::new));
    try (var pool = Executors.newFixedThreadPool(4)) {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        var r = relay(pub, 10);
        futures.add(
            pool.submit(
                () -> {
                  int total = 0;
                  int sent;
                  do {
                    sent = r.relayOnce();
                    total += sent;
                  } while (sent > 0);
                  return total;
                }));
      }
      int sum = 0;
      for (var f : futures) {
        sum += f.get();
      }
      assertThat(sum).isEqualTo(60);
    }
    assertThat(pub.published).hasSize(60);
    assertThat(pub.published.stream().map(CloudEvent::id).distinct().count()).isEqualTo(60);
    assertThat(count("SELECT count(*) FROM outbox WHERE published_at IS NULL")).isZero();
  }

  @Test
  void validateFlagRejectsAnEventThatBreaksItsSchema() {
    var bad =
        CloudEvents.create(
            "scheduling",
            "shp.scheduling.appointment-booked.v1",
            "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70",
            Map.of("appointmentId", "x"));
    assertThatThrownBy(() -> TX.executeWithoutResult(s -> outbox.add(bad, true)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void backgroundLoopPublishesWithoutBeingAsked() throws Exception {
    var pub = new Recording();
    add(evt(1));
    var r = new OutboxRelay(JDBC, TX, pub, Clock.systemUTC(), 100, Duration.ofMillis(50));
    r.start();
    try {
      long deadline = System.currentTimeMillis() + 5000;
      while (pub.published.isEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertThat(pub.published).hasSize(1);
      assertThat(r.isRunning()).isTrue();
    } finally {
      r.stop();
    }
    assertThat(r.isRunning()).isFalse();
  }
}
