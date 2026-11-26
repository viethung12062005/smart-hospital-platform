package org.shp.scheduling.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hl7.fhir.r4.model.Appointment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.data.postgres.PostgresOutbox;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.domain.AppointmentFhir;
import org.shp.scheduling.domain.AppointmentRecord;
import org.shp.scheduling.infra.AppointmentRepository;
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
 * SCH-24 (US-020): Integration tests for {@link NoShowMarkingJob} on PostgreSQL 16. Verifies the
 * {@code end + 30 min} cutoff, FHIR/DB/history updates, schema-valid {@code
 * shp.scheduling.appointment-status-changed.v1} (E5) emission, batch draining, and {@code FOR
 * UPDATE SKIP LOCKED} concurrency across two replicas.
 */
@SpringBootTest(
    properties = {
      "shp.auth.authority=https://idp.test/realms/shp",
      "shp.auth.audience=shp-api",
      "shp.events.validate-schema=true",
      "shp.scheduling.no-show.cron=-"
    })
@Testcontainers
class NoShowMarkingJobTest {
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

  @Autowired NoShowMarkingJob scheduledBean;
  @Autowired AppointmentRepository appointments;
  @Autowired PostgresOutbox outbox;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;

  private BookingTestSupport data;
  private UUID doctor;
  private UUID schedule;

  @BeforeEach
  void seed() {
    data = new BookingTestSupport(jdbc);
    data.clean();
    doctor = UUID.randomUUID();
    schedule = data.schedule(doctor, "cardiology");
  }

  private AppointmentRecord seedAppointment(Instant start, Instant end, String status) {
    return seedAppointment(schedule, doctor, start, end, status);
  }

  private AppointmentRecord seedAppointment(
      UUID scheduleId, UUID practitionerId, Instant start, Instant end, String status) {
    UUID slotId = data.slot(scheduleId, practitionerId, "cardiology", "busy", start);
    UUID apptId = UUID.randomUUID();
    UUID patientId = UUID.randomUUID();
    Instant createdAt = start.minus(1, ChronoUnit.DAYS);
    AppointmentRecord draft =
        new AppointmentRecord(
            apptId,
            1,
            createdAt,
            "booked",
            slotId,
            patientId,
            practitionerId,
            "cardiology",
            start,
            end,
            "web",
            "seed-user",
            "{}");
    AppointmentRecord booked = draft.withContent(AppointmentFhir.booked(draft, null));
    String stored = appointments.insertWithHistory(booked);
    if (!"booked".equals(status)) {
      String cancelReason = "cancelled".equals(status) ? "patient-request" : null;
      jdbc.sql("UPDATE scheduling.appointment SET status = ?, cancel_reason = ? WHERE id = ?")
          .params(status, cancelReason, apptId)
          .update();
    }
    return booked.withContent(stored);
  }

  private NoShowMarkingJob newReplicaAt(Instant now) {
    return new NoShowMarkingJob(appointments, outbox, tx, Clock.fixed(now, ZoneOffset.UTC), true);
  }

  private long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  @Test
  @DisplayName(
      "booked past end + 30 min becomes noshow with history and schema-valid E5 event; others untouched")
  void bookedPastEndPlus30MinutesBecomesNoshowWithHistoryAndSchemaValidEvent() {
    Instant now = Instant.parse("2026-12-05T08:45:01Z");

    AppointmentRecord overdue =
        seedAppointment(
            Instant.parse("2026-12-05T08:00:00Z"), Instant.parse("2026-12-05T08:15:00Z"), "booked");
    // Exact boundary on another schedule: end + 30m == now (must not be marked; strictly end + 30m
    // < now)
    UUID otherDoctor = UUID.randomUUID();
    UUID otherSchedule = data.schedule(otherDoctor, "cardiology");
    AppointmentRecord exactBoundary =
        seedAppointment(
            otherSchedule,
            otherDoctor,
            Instant.parse("2026-12-05T08:00:01Z"),
            Instant.parse("2026-12-05T08:15:01Z"),
            "booked");
    AppointmentRecord recent =
        seedAppointment(
            Instant.parse("2026-12-05T08:15:00Z"), Instant.parse("2026-12-05T08:30:00Z"), "booked");

    AppointmentRecord arrived =
        seedAppointment(
            Instant.parse("2026-12-05T07:00:00Z"),
            Instant.parse("2026-12-05T07:15:00Z"),
            "arrived");
    AppointmentRecord cancelled =
        seedAppointment(
            Instant.parse("2026-12-05T07:15:00Z"),
            Instant.parse("2026-12-05T07:30:00Z"),
            "cancelled");
    AppointmentRecord fulfilled =
        seedAppointment(
            Instant.parse("2026-12-05T07:30:00Z"),
            Instant.parse("2026-12-05T07:45:00Z"),
            "fulfilled");
    AppointmentRecord alreadyNoShow =
        seedAppointment(
            Instant.parse("2026-12-05T07:45:00Z"), Instant.parse("2026-12-05T08:00:00Z"), "noshow");

    int marked = newReplicaAt(now).markOverdueNoShows();
    assertThat(marked).isEqualTo(1);

    AppointmentRecord updated = appointments.findById(overdue.id()).orElseThrow();
    assertThat(updated.status()).isEqualTo("noshow");
    assertThat(updated.versionId()).isEqualTo(2);
    Appointment fhir = FhirJson.parse(updated.content(), Appointment.class);
    assertThat(fhir.getStatus()).isEqualTo(Appointment.AppointmentStatus.NOSHOW);
    assertThat(fhir.getMeta().getVersionId()).isEqualTo("2");

    // Slot stays busy for terminal noshow (01-domain-model §4.5)
    assertThat(
            jdbc.sql("SELECT status FROM scheduling.slot WHERE id = ?")
                .param(overdue.slotId())
                .query(String.class)
                .single())
        .isEqualTo("busy");

    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM scheduling.resource_history WHERE resource_type = 'Appointment' AND id = ? AND version_id = 2 AND operation = 'update' AND changed_by = ?")
                .params(overdue.id(), NoShowMarkingJob.CHANGED_BY)
                .query(Long.class)
                .single())
        .isEqualTo(1);

    String payload =
        jdbc.sql("SELECT payload::text FROM scheduling.outbox WHERE event_type = ?")
            .param(NoShowMarkingJob.EVENT_TYPE)
            .query(String.class)
            .single();
    CloudEvent evt = CloudEvents.parse(payload);
    assertThat(EventSchemas.validate(evt)).isEmpty();
    assertThat(evt.subject()).isEqualTo("Appointment/" + overdue.id());
    assertThat(evt.data().get("appointmentId").asString()).isEqualTo(overdue.id().toString());
    assertThat(evt.data().get("version").asInt()).isEqualTo(2);
    assertThat(evt.data().get("patientId").asString()).isEqualTo(overdue.patientId().toString());
    assertThat(evt.data().get("previousStatus").asString()).isEqualTo("booked");
    assertThat(evt.data().get("newStatus").asString()).isEqualTo("noshow");
    assertThat(evt.data().get("changedAt").asString()).isEqualTo(now.toString());
    Set<String> fields = new TreeSet<>();
    evt.data().propertyNames().forEach(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "appointmentId", "version", "patientId", "previousStatus", "newStatus", "changedAt");

    assertThat(appointments.findById(exactBoundary.id()).orElseThrow().status())
        .isEqualTo("booked");
    assertThat(appointments.findById(recent.id()).orElseThrow().status()).isEqualTo("booked");
    assertThat(appointments.findById(arrived.id()).orElseThrow().status()).isEqualTo("arrived");
    assertThat(appointments.findById(cancelled.id()).orElseThrow().status()).isEqualTo("cancelled");
    assertThat(appointments.findById(fulfilled.id()).orElseThrow().status()).isEqualTo("fulfilled");
    assertThat(appointments.findById(alreadyNoShow.id()).orElseThrow().status())
        .isEqualTo("noshow");

    // Safe to re-run
    assertThat(newReplicaAt(now).markOverdueNoShows()).isZero();
    assertThat(count("SELECT count(*) FROM scheduling.outbox")).isEqualTo(1);
  }

  @Test
  @DisplayName("two replicas running concurrently with SKIP LOCKED do not double-process rows")
  void twoReplicasRunningConcurrentlyDoNotDoubleProcess() throws Exception {
    Instant now = Instant.parse("2026-12-05T10:00:00Z");
    for (int i = 0; i < 4; i++) {
      Instant start = Instant.parse("2026-12-05T08:00:00Z").plusSeconds(900L * i);
      seedAppointment(start, start.plusSeconds(900), "booked");
    }

    NoShowMarkingJob replica1 = newReplicaAt(now);
    NoShowMarkingJob replica2 = newReplicaAt(now);

    CountDownLatch locksHeld = new CountDownLatch(1);
    CountDownLatch releaseReplica1 = new CountDownLatch(1);

    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<Integer> future1 =
          executor.submit(() ->
              replica1.markOverdueBatch(
                  NoShowMarkingJob.DEFAULT_BATCH_SIZE,
                  () -> {
                    locksHeld.countDown();
                    try {
                      if (!releaseReplica1.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release replica1");
                      }
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException(e);
                    }
                  }));

      assertThat(locksHeld.await(15, TimeUnit.SECONDS)).isTrue();

      // Replica 2 skips locked rows and finishes immediately without duplicating updates or events.
      int processedByReplica2 =
          replica2.markOverdueBatch(NoShowMarkingJob.DEFAULT_BATCH_SIZE, () -> {});
      assertThat(processedByReplica2).isZero();

      releaseReplica1.countDown();
      assertThat(future1.get(15, TimeUnit.SECONDS)).isEqualTo(4);
    }

    assertThat(count("SELECT count(*) FROM scheduling.appointment WHERE status = 'noshow'"))
        .isEqualTo(4);
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.resource_history WHERE resource_type = 'Appointment' AND version_id = 2 AND operation = 'update'"))
        .isEqualTo(4);
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.outbox WHERE event_type = 'shp.scheduling.appointment-status-changed.v1'"))
        .isEqualTo(4);
  }

  @Test
  @DisplayName(
      "two replicas with smaller batch size partition overdue rows cleanly via SKIP LOCKED")
  void twoReplicasWithSmallerBatchPartitionOverdueRowsWithoutOverlap() throws Exception {
    Instant now = Instant.parse("2026-12-05T10:00:00Z");
    for (int i = 0; i < 4; i++) {
      Instant start = Instant.parse("2026-12-05T08:00:00Z").plusSeconds(900L * i);
      seedAppointment(start, start.plusSeconds(900), "booked");
    }

    NoShowMarkingJob replica1 = newReplicaAt(now);
    NoShowMarkingJob replica2 = newReplicaAt(now);

    CountDownLatch locksHeld = new CountDownLatch(1);
    CountDownLatch releaseReplica1 = new CountDownLatch(1);

    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<Integer> future1 =
          executor.submit(() ->
              replica1.markOverdueBatch(
                  2,
                  () -> {
                    locksHeld.countDown();
                    try {
                      if (!releaseReplica1.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release replica1");
                      }
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException(e);
                    }
                  }));

      assertThat(locksHeld.await(15, TimeUnit.SECONDS)).isTrue();

      int processedByReplica2 = replica2.markOverdueBatch(2, () -> {});
      assertThat(processedByReplica2).isEqualTo(2);

      releaseReplica1.countDown();
      assertThat(future1.get(15, TimeUnit.SECONDS)).isEqualTo(2);
    }

    assertThat(count("SELECT count(*) FROM scheduling.appointment WHERE status = 'noshow'"))
        .isEqualTo(4);
    assertThat(
            jdbc.sql(
                    "SELECT count(DISTINCT payload->'data'->>'appointmentId') FROM scheduling.outbox WHERE event_type = 'shp.scheduling.appointment-status-changed.v1'")
                .query(Long.class)
                .single())
        .isEqualTo(4);
  }

  @Test
  @DisplayName("markOverdueNoShows drains multiple batches and scheduled bean is registered")
  void markOverdueNoShowsDrainsAcrossMultipleBatches() {
    assertThat(scheduledBean).isNotNull();

    Instant now = Instant.parse("2026-12-07T00:00:00Z");
    Instant baseStart = Instant.parse("2026-12-01T00:00:00Z");
    for (int i = 0; i < NoShowMarkingJob.DEFAULT_BATCH_SIZE + 2; i++) {
      Instant start = baseStart.plusSeconds(900L * i);
      seedAppointment(start, start.plusSeconds(900), "booked");
    }

    int marked = newReplicaAt(now).markOverdueNoShows();
    assertThat(marked).isEqualTo(NoShowMarkingJob.DEFAULT_BATCH_SIZE + 2);
    assertThat(count("SELECT count(*) FROM scheduling.appointment WHERE status = 'noshow'"))
        .isEqualTo(NoShowMarkingJob.DEFAULT_BATCH_SIZE + 2);
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.outbox WHERE event_type = 'shp.scheduling.appointment-status-changed.v1'"))
        .isEqualTo(NoShowMarkingJob.DEFAULT_BATCH_SIZE + 2);
  }
}
