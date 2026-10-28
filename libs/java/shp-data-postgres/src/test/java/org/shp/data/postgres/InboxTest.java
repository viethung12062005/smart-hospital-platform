package org.shp.data.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
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

class InboxTest extends PostgresTestBase {
  final PostgresInbox inbox = new PostgresInbox(JDBC, TX, Clock.systemUTC());

  static CloudEvent evt() {
    return CloudEvents.create(
        "clinical",
        "shp.clinical.encounter-finished.v1",
        "Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e05",
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
            3));
  }

  @Test
  void sameEventDeliveredTwiceIsHandledOnce() {
    var e = evt();
    var runs = new AtomicInteger();
    Runnable h =
        () -> {
          runs.incrementAndGet();
          insertThing("x");
        };
    assertThat(inbox.handleOnce("scheduling", e, h)).isTrue();
    assertThat(inbox.handleOnce("scheduling", e, h)).isFalse();
    assertThat(runs).hasValue(1);
    assertThat(count("SELECT count(*) FROM thing")).isEqualTo(1);
  }

  @Test
  void differentConsumersEachHandleTheSameEvent() {
    var e = evt();
    assertThat(inbox.handleOnce("scheduling", e, () -> insertThing("a"))).isTrue();
    assertThat(inbox.handleOnce("notification", e, () -> insertThing("b"))).isTrue();
    assertThat(count("SELECT count(*) FROM inbox")).isEqualTo(2);
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
    assertThat(count("SELECT count(*) FROM thing")).isZero();
    assertThat(count("SELECT count(*) FROM inbox")).isZero();
    assertThat(inbox.handleOnce("c", e, () -> insertThing("ok"))).isTrue();
    assertThat(count("SELECT count(*) FROM thing")).isEqualTo(1);
  }

  @Test
  void concurrentDeliveriesRunTheHandlerExactlyOnce() throws Exception {
    var e = evt();
    var runs = new AtomicInteger();
    List<Future<Boolean>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(20)) {
      for (int i = 0; i < 20; i++) {
        results.add(
            pool.submit(
                () ->
                    inbox.handleOnce(
                        "scheduling",
                        e,
                        () -> {
                          runs.incrementAndGet();
                          insertThing("x");
                        })));
      }
      int ok = 0;
      for (var f : results) {
        ok += f.get() ? 1 : 0;
      }
      assertThat(ok).isEqualTo(1);
    }
    assertThat(runs).hasValue(1);
    assertThat(count("SELECT count(*) FROM thing")).isEqualTo(1);
  }

  @Test
  void handlerCanWriteDataAndOutboxInOneTransaction() {
    var outbox = new PostgresOutbox(JDBC);
    inbox.handleOnce(
        "scheduling",
        evt(),
        () -> {
          insertThing("fulfilled");
          outbox.add(
              CloudEvents.create(
                  "scheduling",
                  "shp.scheduling.appointment-status-changed.v1",
                  "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e00",
                  Map.of(
                      "appointmentId",
                      Uuids.v7(),
                      "version",
                      4,
                      "patientId",
                      Uuids.v7(),
                      "previousStatus",
                      "arrived",
                      "newStatus",
                      "fulfilled",
                      "changedAt",
                      "2026-12-03T02:00:00Z")),
              true);
        });
    assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM thing")).isEqualTo(1);
  }
}
