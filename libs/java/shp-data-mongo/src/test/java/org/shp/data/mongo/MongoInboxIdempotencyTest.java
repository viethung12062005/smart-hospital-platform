package org.shp.data.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.Uuids;
import org.shp.web.errors.ShpException;
import org.shp.web.idempotency.IdempotencyKeys;

class MongoInboxIdempotencyTest extends MongoTestBase {
  static final String KEY = "7b0e6c52-1111-4f3a-9d11-aaaaaaaaaaaa";
  final MongoInbox inbox = new MongoInbox(DB, TX, Clock.systemUTC());
  final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T02:00:00Z"));
  final MongoIdempotency idem = new MongoIdempotency(DB, clock);

  static CloudEvent evt() {
    return CloudEvents.create(
        "scheduling",
        "shp.scheduling.appointment-checked-in.v1",
        "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e05",
        Map.of(
            "appointmentId",
            Uuids.v7(),
            "version",
            3,
            "patientId",
            Uuids.v7(),
            "practitionerId",
            Uuids.v7(),
            "specialty",
            "cardiology",
            "checkedInAt",
            "2026-12-03T01:15:00Z"));
  }

  // ---------- inbox ----------
  @Test
  void sameEventDeliveredTwiceIsHandledOnce() {
    var e = evt();
    var runs = new AtomicInteger();
    Runnable h =
        () -> {
          runs.incrementAndGet();
          insertThing("x");
        };
    assertThat(inbox.handleOnce("clinical", e, h)).isTrue();
    assertThat(inbox.handleOnce("clinical", e, h)).isFalse();
    assertThat(runs).hasValue(1);
    assertThat(count("thing")).isEqualTo(1);
  }

  @Test
  void differentConsumersEachHandleTheSameEvent() {
    var e = evt();
    assertThat(inbox.handleOnce("clinical", e, () -> insertThing("a"))).isTrue();
    assertThat(inbox.handleOnce("notification", e, () -> insertThing("b"))).isTrue();
    assertThat(count(MongoMessaging.INBOX)).isEqualTo(2);
  }

  @Test
  void failingHandlerRollsEverythingBackSoRedeliveryIsProcessed() {
    var e = evt();
    assertThatThrownBy(
            () ->
                inbox.handleOnce(
                    "c",
                    e,
                    () -> {
                      insertThing("half-done");
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("thing")).isZero();
    assertThat(count(MongoMessaging.INBOX)).isZero();
    assertThat(inbox.handleOnce("c", e, () -> insertThing("ok"))).isTrue();
    assertThat(count("thing")).isEqualTo(1);
  }

  @Test
  void concurrentDeliveriesRunTheHandlerExactlyOnce() throws Exception {
    var e = evt();
    var committedRuns = new AtomicInteger();
    List<Future<Boolean>> fs = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(20)) {
      for (int i = 0; i < 20; i++) {
        fs.add(pool.submit(() -> inbox.handleOnce("clinical", e, () -> insertThing("x"))));
      }
      for (var f : fs) {
        committedRuns.addAndGet(f.get() ? 1 : 0);
      }
    }
    assertThat(committedRuns).hasValue(1);
    assertThat(count("thing"))
        .isEqualTo(
            1); // handler có thể chạy lại khi transaction xung đột, nhưng chỉ một lần được commit
  }

  @Test
  void handlerCanWriteDataAndOutboxInOneTransaction() {
    var outbox = new MongoOutbox(DB);
    inbox.handleOnce(
        "clinical",
        evt(),
        () -> {
          insertThing("encounter");
          outbox.add(
              CloudEvents.create(
                  "clinical",
                  "shp.clinical.encounter-finished.v1",
                  "Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e00",
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
                      1)),
              true);
        });
    assertThat(count(MongoMessaging.OUTBOX)).isEqualTo(1);
    assertThat(count("thing")).isEqualTo(1);
  }

  // ---------- idempotency ----------
  static byte[] hash(String json) {
    return IdempotencyKeys.hashBody(json.getBytes(StandardCharsets.UTF_8));
  }

  MongoIdempotency.Claim claim(String subject, String key, String op, String body, boolean commit) {
    return MongoMessaging.inTransaction(
        new org.springframework.transaction.support.TransactionTemplate(TX.getTransactionManager()),
        () -> {
          var c = idem.claim(subject, key, op, hash(body));
          if (!commit) {
            throw new RollbackRequested(c);
          }
          return c;
        });
  }

  static final class RollbackRequested extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final transient MongoIdempotency.Claim claim;

    RollbackRequested(MongoIdempotency.Claim claim) {
      this.claim = claim;
    }
  }

  MongoIdempotency.Claim claim() {
    return claim("sub-1", KEY, "DocumentReference/$upload", "{\"size\":1}", true);
  }

  @Test
  void firstRequestClaimsAndSameRequestReplays() {
    var first = claim();
    assertThat(first.replay()).isFalse();
    var again = claim();
    assertThat(again.replay()).isTrue();
    assertThat(again.resourceId()).isEqualTo(first.resourceId());
  }

  @Test
  void sameKeyWithADifferentBodyOrOperationIs422() {
    claim();
    assertThatThrownBy(() -> claim("sub-1", KEY, "DocumentReference/$upload", "{\"size\":2}", true))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().status()).isEqualTo(422));
    assertThatThrownBy(() -> claim("sub-1", KEY, "other", "{\"size\":1}", true))
        .isInstanceOf(ShpException.class);
  }

  @Test
  void keysAreScopedPerCaller() {
    var a = claim("alice", KEY, "op", "{}", true);
    var b = claim("bob", KEY, "op", "{}", true);
    assertThat(b.replay()).isFalse();
    assertThat(b.resourceId()).isNotEqualTo(a.resourceId());
  }

  @Test
  void parallelRequestsWithOneKeyProduceExactlyOneNewClaim() throws Exception {
    List<Future<MongoIdempotency.Claim>> fs = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(20)) {
      for (int i = 0; i < 20; i++) {
        fs.add(pool.submit(() -> claim()));
      }
      List<MongoIdempotency.Claim> claims = new ArrayList<>();
      for (var f : fs) {
        claims.add(f.get());
      }
      assertThat(claims.stream().filter(c -> !c.replay()).count()).isEqualTo(1);
      assertThat(claims.stream().map(MongoIdempotency.Claim::resourceId).distinct().count())
          .isEqualTo(1);
    }
    assertThat(count(MongoMessaging.IDEMPOTENCY)).isEqualTo(1);
  }

  @Test
  void rolledBackClaimCanBeClaimedAgain() {
    assertThatThrownBy(() -> claim("sub-1", KEY, "op", "{}", false))
        .isInstanceOf(RollbackRequested.class);
    assertThat(claim("sub-1", KEY, "op", "{}", true).replay()).isFalse();
  }

  @Test
  void keyExpiresAfter24Hours() {
    var first = claim();
    clock.now = clock.now.plusSeconds(25 * 3600);
    var later = claim();
    assertThat(later.replay()).isFalse();
    assertThat(later.resourceId()).isNotEqualTo(first.resourceId());
  }

  @Test
  void claimOutsideATransactionIsRefused() {
    assertThatThrownBy(() -> idem.claim("s", KEY, "op", hash("{}")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void ttlIndexesExist() {
    assertThat(DB.indexOps(MongoMessaging.INBOX).getIndexInfo())
        .anyMatch(i -> i.getName().equals("ttl_inbox_14d"));
    assertThat(DB.indexOps(MongoMessaging.IDEMPOTENCY).getIndexInfo())
        .anyMatch(i -> i.getName().equals("ttl_idempotency_24h"));
  }
}
