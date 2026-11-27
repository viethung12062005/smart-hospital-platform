package org.shp.scheduling.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.scheduling.app.DailyCleanupJob.RunResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-28 (US-015): Integration tests for {@link DailyCleanupJob} on PostgreSQL 16. Verifies the
 * retention thresholds, that data still needed is kept, that reruns are safe and that only one
 * replica runs at a time.
 */
@Testcontainers
class DailyCleanupJobTest {

  @Container
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  // 2026-11-07 02:00:00+07:00
  private static final Instant NOW = Instant.parse("2026-11-06T19:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static SimpleDriverDataSource dataSource;
  private static JdbcClient jdbc;
  private static UUID scheduleId;

  @BeforeAll
  static void setup() {
    String url = DB.getJdbcUrl();
    url += (url.contains("?") ? "&" : "?") + "currentSchema=scheduling,public";
    dataSource =
        new SimpleDriverDataSource(
            new org.postgresql.Driver(), url, DB.getUsername(), DB.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .initSql("CREATE SCHEMA IF NOT EXISTS scheduling; SET search_path = scheduling, public")
        .locations("classpath:db/migration", "classpath:db/shp-messaging/postgres")
        .load()
        .migrate();
    jdbc = JdbcClient.create(dataSource);
  }

  @BeforeEach
  void reset() {
    jdbc.sql(
            "TRUNCATE slot, appointment, schedule_template, schedule, resource_history,"
                + " outbox, idempotency_key CASCADE")
        .update();
    scheduleId = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO schedule (id, last_updated, active, practitioner_id, specialty,"
                + " horizon_start, horizon_end, content)"
                + " VALUES (?, ?, true, ?, 'cardiology', DATE '2026-01-01', DATE '2026-12-31', '{}')")
        .params(scheduleId, Timestamp.from(NOW), UUID.randomUUID())
        .update();
  }

  private static DailyCleanupJob newReplicaJob() {
    return new DailyCleanupJob(
        JdbcClient.create(dataSource),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        CLOCK);
  }

  private static Timestamp ago(Duration d) {
    return Timestamp.from(NOW.minus(d));
  }

  private static UUID outbox(Duration publishedAgo) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO outbox (id, event_type, subject, payload, occurred_at, published_at)"
                + " VALUES (?, 'e', 's', '{}'::jsonb, ?, ?)")
        .params(id, ago(Duration.ofDays(200)), publishedAgo == null ? null : ago(publishedAgo))
        .update();
    return id;
  }

  private static void idempotency(String key, Duration createdAgo) {
    jdbc.sql(
            "INSERT INTO idempotency_key (subject, key, operation, request_hash, resource_id,"
                + " created_at) VALUES ('sub', ?, 'book', ?, ?, ?)")
        .params(key, new byte[] {1}, UUID.randomUUID(), ago(createdAgo))
        .update();
  }

  /** Slot that ended {@code endedAgo} before NOW. */
  private static UUID slot(String status, Duration endedAgo) {
    UUID id = UUID.randomUUID();
    Instant end = NOW.minus(endedAgo);
    jdbc.sql(
            "INSERT INTO slot (id, last_updated, schedule_id, practitioner_id, specialty, status,"
                + " start_at, end_at, content)"
                + " VALUES (?, ?, ?, ?, 'cardiology', ?, ?, ?, '{}'::jsonb)")
        .params(
            id,
            Timestamp.from(NOW),
            scheduleId,
            UUID.randomUUID(),
            status,
            Timestamp.from(end.minus(Duration.ofMinutes(15))),
            Timestamp.from(end))
        .update();
    return id;
  }

  private static void cancelledAppointment(UUID slotId) {
    jdbc.sql(
            "INSERT INTO appointment (id, last_updated, status, slot_id, patient_id,"
                + " practitioner_id, specialty, start_at, end_at, channel, booked_by,"
                + " cancel_reason, content)"
                + " SELECT ?, ?, 'cancelled', s.id, ?, s.practitioner_id, s.specialty, s.start_at,"
                + " s.end_at, 'web', 'sub', 'patient request', '{}'::jsonb FROM slot s WHERE s.id = ?")
        .params(UUID.randomUUID(), Timestamp.from(NOW), UUID.randomUUID(), slotId)
        .update();
  }

  private static boolean exists(String table, String idColumn, Object id) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + idColumn + " = ?")
            .param(id)
            .query(Long.class)
            .single()
        == 1L;
  }

  private static long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  @Test
  @DisplayName("Published outbox older than 7 days is deleted; recent and unpublished rows stay")
  void outboxRetention() {
    UUID old = outbox(Duration.ofDays(8));
    UUID recent = outbox(Duration.ofDays(6));
    UUID unpublishedOld = outbox(null);

    RunResult result = newReplicaJob().runCleanup();

    assertThat(result.executed()).isTrue();
    assertThat(result.outboxDeleted()).isEqualTo(1);
    assertThat(exists("outbox", "id", old)).isFalse();
    assertThat(exists("outbox", "id", recent)).isTrue();
    assertThat(exists("outbox", "id", unpublishedOld)).isTrue();
  }

  @Test
  @DisplayName("Idempotency keys older than 24 hours are deleted; newer ones stay")
  void idempotencyRetention() {
    idempotency("key-old-0001", Duration.ofHours(25));
    idempotency("key-new-0001", Duration.ofHours(23));

    RunResult result = newReplicaJob().runCleanup();

    assertThat(result.idempotencyDeleted()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT key FROM idempotency_key").query(String.class).list())
        .containsExactly("key-new-0001");
  }

  @Test
  @DisplayName(
      "Only free slots past 90 days are deleted; busy, blocked, recent and referenced stay")
  void freeSlotRetention() {
    UUID oldFree = slot("free", Duration.ofDays(91));
    UUID recentFree = slot("free", Duration.ofDays(89));
    UUID oldBusy = slot("busy", Duration.ofDays(200).plusHours(1));
    UUID oldBlocked = slot("busy-unavailable", Duration.ofDays(201));
    UUID oldFreeWithHistory = slot("free", Duration.ofDays(202));
    cancelledAppointment(oldFreeWithHistory);
    UUID futureFree = slot("free", Duration.ofDays(-5));

    RunResult result = newReplicaJob().runCleanup();

    assertThat(result.slotsDeleted()).isEqualTo(1);
    assertThat(exists("slot", "id", oldFree)).isFalse();
    for (UUID kept : new UUID[] {recentFree, oldBusy, oldBlocked, oldFreeWithHistory, futureFree}) {
      assertThat(exists("slot", "id", kept)).isTrue();
    }
    assertThat(count("appointment")).isEqualTo(1);
  }

  @Test
  @DisplayName("Rows exactly at the threshold are kept and a rerun deletes nothing more")
  void thresholdAndRerun() {
    outbox(Duration.ofDays(7));
    idempotency("key-edge-001", Duration.ofHours(24));
    slot("free", Duration.ofDays(90));
    outbox(Duration.ofDays(30));

    RunResult first = newReplicaJob().runCleanup();
    RunResult second = newReplicaJob().runCleanup();

    assertThat(first.outboxDeleted()).isEqualTo(1);
    assertThat(first.idempotencyDeleted()).isZero();
    assertThat(first.slotsDeleted()).isZero();
    assertThat(second.outboxDeleted()).isZero();
    assertThat(count("outbox")).isEqualTo(1);
    assertThat(count("idempotency_key")).isEqualTo(1);
    assertThat(count("slot")).isEqualTo(1);
  }

  @Test
  @DisplayName("More rows than one batch are all deleted")
  void multipleBatches() {
    jdbc.sql(
            "INSERT INTO outbox (id, event_type, subject, payload, occurred_at, published_at)"
                + " SELECT gen_random_uuid(), 'e', 's', '{}'::jsonb, ?, ?"
                + " FROM generate_series(1, ?)")
        .params(ago(Duration.ofDays(30)), ago(Duration.ofDays(30)), DailyCleanupJob.BATCH_SIZE + 5)
        .update();

    RunResult result = newReplicaJob().runCleanup();

    assertThat(result.outboxDeleted()).isEqualTo(DailyCleanupJob.BATCH_SIZE + 5);
    assertThat(count("outbox")).isZero();
  }

  @Test
  @DisplayName("A second replica skips while the first holds the advisory lock")
  void onlyOneReplicaRuns() throws Exception {
    try (var holder = dataSource.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      holder.setAutoCommit(false);
      try (var st = holder.createStatement()) {
        st.execute(
            "SELECT pg_advisory_xact_lock(hashtextextended('"
                + DailyCleanupJob.LOCK_KEY
                + "', 0))");
      }
      CountDownLatch done = new CountDownLatch(1);
      Future<RunResult> skipped =
          executor.submit(() -> {
            try {
              return newReplicaJob().runCleanup();
            } finally {
              done.countDown();
            }
          });
      assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
      assertThat(skipped.get().executed()).isFalse();
      holder.rollback();
    }
    assertThat(newReplicaJob().runCleanup().executed()).isTrue();
  }
}
