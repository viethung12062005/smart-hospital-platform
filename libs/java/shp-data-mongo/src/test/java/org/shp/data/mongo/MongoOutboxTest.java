package org.shp.data.mongo;

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
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventPublisher;
import org.shp.events.EventSchemas;
import org.shp.events.Uuids;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

class MongoOutboxTest extends MongoTestBase {
  static final Instant T0 = Instant.parse("2026-11-20T02:00:00Z");
  final MongoOutbox outbox = new MongoOutbox(DB);

  static CloudEvent evt(int n) {
    return CloudEvents.create(
        "clinical",
        "shp.clinical.encounter-finished.v1",
        "Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e%02d".formatted(n),
        Map.of(
            "encounterId",
            Uuids.v7(),
            "appointmentId",
            Uuids.v7(),
            "patientId",
            Uuids.v7(),
            "practitionerId",
            Uuids.v7(),
            "finishedAt",
            "2026-12-03T02:00:00Z",
            "version",
            n),
        Clock.fixed(T0.plusSeconds(n), ZoneOffset.UTC),
        null);
  }

  static final class Recording implements EventPublisher {
    final ConcurrentLinkedQueue<CloudEvent> published = new ConcurrentLinkedQueue<>();
    volatile Integer failOnVersion;
    volatile long delayMs;

    @Override
    public void publish(CloudEvent e) {
      if (failOnVersion != null && e.data().get("version").asInt() == failOnVersion) {
        throw new IllegalStateException("broker refused");
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

  MongoOutboxRelay relay(EventPublisher p, int batch, Clock clock) {
    return new MongoOutboxRelay(DB, p, clock, batch, Duration.ofSeconds(1));
  }

  MongoOutboxRelay relay(EventPublisher p, int batch) {
    return relay(p, batch, Clock.systemUTC());
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
    assertThat(count("thing")).isZero();
    assertThat(count(MongoMessaging.OUTBOX)).isZero();
    add(evt(1));
    assertThat(count(MongoMessaging.OUTBOX)).isEqualTo(1);
  }

  @Test
  void addOutsideATransactionIsRefused() {
    assertThatThrownBy(() -> outbox.add(evt(1))).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void relayPublishesInTimeOrderMarksPublishedAndRoundTripsTheEvent() {
    var pub = new Recording();
    var e3 = evt(3);
    add(e3, evt(1), evt(2));
    assertThat(relay(pub, 100).relayOnce()).isEqualTo(3);
    assertThat(pub.versions()).containsExactly(1, 2, 3);
    assertThat(relay(pub, 100).relayOnce()).isZero();
    assertThat(pub.published).contains(e3);
    pub.published.forEach(e -> assertThat(EventSchemas.validate(e)).isEmpty());
    assertThat(DB.count(Query.query(Criteria.where("publishedAt").ne(null)), MongoMessaging.OUTBOX))
        .isEqualTo(3);
  }

  @Test
  void busOutageLosesNothingAndRecoversInOrder() {
    var pub = new Recording();
    pub.failOnVersion = 2;
    add(evt(1), evt(2), evt(3));
    assertThatThrownBy(() -> relay(pub, 100).relayOnce())
        .isInstanceOfSatisfying(
            MongoOutboxRelay.OutboxPublishException.class,
            e -> assertThat(e.publishedBeforeFailure()).isEqualTo(1));
    var failed =
        DB.findOne(
            Query.query(Criteria.where("subject").regex("e02$")),
            Document.class,
            MongoMessaging.OUTBOX);
    assertThat(failed.getInteger("attempts")).isEqualTo(1);
    assertThat(failed.getString("lastError"))
        .contains("broker refused")
        .doesNotContain("patientId");
    assertThat(failed.get("lockedUntil")).isNull(); // nhả khóa để thử lại ngay
    pub.failOnVersion = null;
    assertThat(relay(pub, 100).relayOnce()).isEqualTo(2);
    assertThat(pub.versions()).containsExactly(1, 2, 3);
  }

  @Test
  void eventLockedByADeadReplicaIsReclaimedAfterTheLeaseExpires() {
    var clock = new MutableClock(Instant.parse("2026-11-20T03:00:00Z"));
    add(evt(1));
    // Một replica giành event rồi chết trước khi phát.
    DB.updateFirst(
        Query.query(Criteria.where("publishedAt").is(null)),
        new org.springframework.data.mongodb.core.query.Update()
            .set("lockedUntil", java.util.Date.from(clock.now.plus(MongoOutboxRelay.LEASE)))
            .set("lockedBy", "dead"),
        MongoMessaging.OUTBOX);
    var pub = new Recording();
    assertThat(relay(pub, 100, clock).relayOnce()).isZero(); // khóa còn hạn: không ai được phát
    clock.now = clock.now.plus(MongoOutboxRelay.LEASE).plusSeconds(1);
    assertThat(relay(pub, 100, clock).relayOnce()).isEqualTo(1);
  }

  @Test
  void severalReplicasNeverPublishAnEventTwice() throws Exception {
    var pub = new Recording();
    pub.delayMs = 3;
    add(IntStream.rangeClosed(1, 60).mapToObj(MongoOutboxTest::evt).toArray(CloudEvent[]::new));
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
  }

  @Test
  void validateFlagRejectsAnEventThatBreaksItsSchema() {
    var bad =
        CloudEvents.create(
            "clinical",
            "shp.clinical.encounter-finished.v1",
            "Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70",
            Map.of("encounterId", "x"));
    assertThatThrownBy(() -> TX.executeWithoutResult(s -> outbox.add(bad, true)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void backgroundLoopPublishesWithoutBeingAsked() throws Exception {
    var pub = new Recording();
    add(evt(1));
    var r = new MongoOutboxRelay(DB, pub, Clock.systemUTC(), 100, Duration.ofMillis(50));
    r.start();
    try {
      long deadline = System.currentTimeMillis() + 5000;
      while (pub.published.isEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertThat(pub.published).hasSize(1);
    } finally {
      r.stop();
    }
    assertThat(r.isRunning()).isFalse();
  }
}
