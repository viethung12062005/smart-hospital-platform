package org.shp.scheduling.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.data.postgres.PostgresOutbox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-24 (US-020): the no-show job on real PostgreSQL 16 - a booked appointment past end + 30
 * minutes becomes {@code noshow} with history and an E5 event; two replicas never double-process.
 */
@SpringBootTest(
    properties = {
      "shp.auth.authority=https://idp.test/realms/shp",
      "shp.auth.audience=shp-api",
      "shp.scheduling.no-show.cron=-"
    })
@Testcontainers
class NoShowJobTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  @Container @ServiceConnection
  static final RabbitMQContainer BUS =
      new RabbitMQContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_RABBITMQ_IMAGE", "rabbitmq:4-management"))
              .asCompatibleSubstituteFor("rabbitmq"));

  private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired PostgresOutbox outbox;

  private BookingTestSupport data;
  private UUID doctor;
  private UUID schedule;
  private int counter;

  @BeforeEach
  void seed() {
    data = new BookingTestSupport(jdbc);
    data.clean();
    doctor = UUID.randomUUID();
    schedule = data.schedule(doctor, "cardiology");
  }

  private NoShowJob job() {
    return new NoShowJob(jdbc, tx, outbox, CLOCK, true);
  }

  /** An appointment whose slot (15 min) ends {@code endedAgoMinutes} before NOW. */
  private UUID appointment(String status, long endedAgoMinutes) {
    Instant start = NOW.minusSeconds(endedAgoMinutes * 60 + 900);
    // distinct start per slot keeps the seed unique even when ends coincide
    UUID slot = data.slot(schedule, doctor, "cardiology", "busy", start.minusSeconds(counter++));
    return data.appointment(slot, UUID.randomUUID(), status, start);
  }

  private String status(UUID id) {
    return jdbc.sql("SELECT status FROM scheduling.appointment WHERE id = ?")
        .param(id)
        .query(String.class)
        .single();
  }

  private int events() {
    return jdbc.sql("SELECT count(*) FROM outbox WHERE event_type = ?")
        .param(NoShowJob.EVENT_TYPE)
        .query(Integer.class)
        .single();
  }

  @Test
  @DisplayName("booked past end + 30 min becomes noshow with version, history and one E5 event")
  void marksOverdueBooked() {
    UUID overdue = appointment("booked", 31);

    assertThat(job().run()).isEqualTo(1);

    assertThat(status(overdue)).isEqualTo("noshow");
    assertThat(
            jdbc.sql("SELECT version_id FROM scheduling.appointment WHERE id = ?")
                .param(overdue)
                .query(Integer.class)
                .single())
        .isEqualTo(2);
    assertThat(
            jdbc.sql(
                    "SELECT content->>'status' FROM scheduling.resource_history WHERE id = ? AND version_id = 2 AND operation = 'update' AND changed_by = 'system'")
                .param(overdue)
                .query(String.class)
                .single())
        .isEqualTo("noshow");
    var payload =
        jdbc.sql("SELECT payload::text FROM outbox WHERE event_type = ?")
            .param(NoShowJob.EVENT_TYPE)
            .query(String.class)
            .single();
    assertThat(payload)
        .contains(overdue.toString(), "\"previousStatus\":\"booked\"", "\"newStatus\":\"noshow\"");
    assertThat(events()).isEqualTo(1);
  }

  @Test
  @DisplayName("not yet 30 min past end, and other statuses, are left alone")
  void leavesOthersAlone() {
    UUID recent = appointment("booked", 29);
    UUID arrived = appointment("arrived", 120);
    UUID fulfilled = appointment("fulfilled", 120);
    UUID cancelled = appointment("cancelled", 120);

    assertThat(job().run()).isZero();

    assertThat(status(recent)).isEqualTo("booked");
    assertThat(status(arrived)).isEqualTo("arrived");
    assertThat(status(fulfilled)).isEqualTo("fulfilled");
    assertThat(status(cancelled)).isEqualTo("cancelled");
    assertThat(events()).isZero();
  }

  @Test
  @DisplayName("a second run does not process the same appointment again")
  void idempotentRerun() {
    appointment("booked", 45);

    assertThat(job().run()).isEqualTo(1);
    assertThat(job().run()).isZero();
    assertThat(events()).isEqualTo(1);
  }

  @Test
  @DisplayName("a row locked by another replica is skipped, not waited on or double-processed")
  void skipsLockedRows() throws Exception {
    UUID locked = appointment("booked", 60);
    UUID free = appointment("booked", 60);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<?> holder =
        pool.submit(() ->
            tx.executeWithoutResult(s -> {
              jdbc.sql("SELECT id FROM scheduling.appointment WHERE id = ? FOR UPDATE")
                  .param(locked)
                  .query(UUID.class)
                  .single();
              holding.countDown();
              try {
                release.await(30, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }));
    try {
      assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(job().run()).isEqualTo(1);
      assertThat(status(free)).isEqualTo("noshow");
      assertThat(status(locked)).isEqualTo("booked");
    } finally {
      release.countDown();
      holder.get(30, TimeUnit.SECONDS);
      pool.shutdown();
    }
    assertThat(job().run()).isEqualTo(1);
    assertThat(status(locked)).isEqualTo("noshow");
    assertThat(events()).isEqualTo(2);
  }

  @Test
  @DisplayName("two replicas running together process every appointment exactly once")
  void twoReplicasNoDoubleProcessing() throws Exception {
    int n = 40;
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add(appointment("booked", 40));
    }
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Integer>> runs = new ArrayList<>();
    for (int r = 0; r < 2; r++) {
      runs.add(
          pool.submit(() -> {
            go.await();
            return job().run();
          }));
    }
    go.countDown();
    int total = 0;
    for (Future<Integer> f : runs) {
      total += f.get(60, TimeUnit.SECONDS);
    }
    pool.shutdown();

    assertThat(total).isEqualTo(n);
    assertThat(events()).isEqualTo(n);
    for (UUID id : ids) {
      assertThat(status(id)).isEqualTo("noshow");
    }
    assertThat(
            jdbc.sql("SELECT count(*) FROM scheduling.resource_history WHERE version_id = 2")
                .query(Integer.class)
                .single())
        .isEqualTo(n);
  }
}
