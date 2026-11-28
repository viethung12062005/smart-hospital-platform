package org.shp.scheduling.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
import org.shp.web.errors.ShpException;
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
 * SCH-20 (US-021): {@link CheckInService} unit/repository tests on real PostgreSQL 16 with fixed
 * clocks verifying the {@code -60/+30 min} window, status transitions, version checks, history, and
 * {@code appointment-checked-in.v1} schema-validated outbox emission.
 */
@SpringBootTest(
    properties = {
      "shp.auth.authority=https://idp.test/realms/shp",
      "shp.auth.audience=shp-api",
      "shp.events.validate-schema=true"
    })
@Testcontainers
class CheckInServiceTest {
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

  @Autowired AppointmentRepository appointments;
  @Autowired PostgresOutbox outbox;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

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
    counter = 0;
  }

  private AppointmentRecord seedAppointment(Instant start, String status) {
    UUID slotId = data.slot(schedule, doctor, "cardiology", "busy", start);
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
            doctor,
            "cardiology",
            start,
            start.plusSeconds(900),
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

  private AppointmentRecord checkInAt(
      Instant now, UUID appointmentId, long expectedVersion, String changedBy) {
    var service = new CheckInService(appointments, outbox, Clock.fixed(now, ZoneOffset.UTC), true);
    return tx.execute(status -> service.checkIn(appointmentId, expectedVersion, changedBy));
  }

  private long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  private Instant slotStart() {
    return Instant.parse("2026-12-05T08:00:00Z").plusSeconds(1800L * ++counter);
  }

  @Test
  @DisplayName(
      "succeeds at exact -60m and +30m boundaries, writes history and schema-valid E4 event")
  void withinCheckInWindowBoundariesSucceedsAndWritesHistoryAndOutbox() {
    Instant startEarly = slotStart();
    AppointmentRecord early = seedAppointment(startEarly, "booked");
    Instant atMinus60 = startEarly.minus(60, ChronoUnit.MINUTES);

    AppointmentRecord checkedInEarly = checkInAt(atMinus60, early.id(), 1, "nurse-1");

    assertThat(checkedInEarly.status()).isEqualTo("arrived");
    assertThat(checkedInEarly.versionId()).isEqualTo(2);
    Appointment fhir = FhirJson.parse(checkedInEarly.content(), Appointment.class);
    assertThat(fhir.getStatus()).isEqualTo(Appointment.AppointmentStatus.ARRIVED);
    assertThat(fhir.getMeta().getVersionId()).isEqualTo("2");

    assertThat(
            jdbc.sql("SELECT status || ':' || version_id FROM scheduling.appointment WHERE id = ?")
                .param(early.id())
                .query(String.class)
                .single())
        .isEqualTo("arrived:2");
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM scheduling.resource_history WHERE resource_type = 'Appointment' AND id = ? AND version_id = 2 AND operation = 'update' AND changed_by = 'nurse-1'")
                .param(early.id())
                .query(Long.class)
                .single())
        .isEqualTo(1);

    String payload =
        jdbc.sql("SELECT payload::text FROM scheduling.outbox WHERE event_type = ? AND subject = ?")
            .params(CheckInService.EVENT_TYPE, "Appointment/" + early.id())
            .query(String.class)
            .single();
    CloudEvent evt = CloudEvents.parse(payload);
    assertThat(EventSchemas.validate(evt)).isEmpty();
    assertThat(evt.data().get("appointmentId").asString()).isEqualTo(early.id().toString());
    assertThat(evt.data().get("version").asInt()).isEqualTo(2);
    assertThat(evt.data().get("patientId").asString()).isEqualTo(early.patientId().toString());
    assertThat(evt.data().get("practitionerId").asString()).isEqualTo(doctor.toString());
    assertThat(evt.data().get("specialty").asString()).isEqualTo("cardiology");
    assertThat(evt.data().get("checkedInAt").asString()).isEqualTo(atMinus60.toString());
    Set<String> fields = new TreeSet<>();
    evt.data().propertyNames().forEach(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "appointmentId", "version", "patientId", "practitionerId", "specialty", "checkedInAt");

    Instant startLate = slotStart();
    AppointmentRecord late = seedAppointment(startLate, "booked");
    AppointmentRecord checkedInLate =
        checkInAt(startLate.plus(30, ChronoUnit.MINUTES), late.id(), 1, "nurse-2");
    assertThat(checkedInLate.status()).isEqualTo("arrived");
    assertThat(checkedInLate.versionId()).isEqualTo(2);
  }

  @Test
  @DisplayName("outside [-60m, +30m] window throws 422 checkin-out-of-window and emits nothing")
  void outsideCheckInWindowReturns422AndEmitsNothing() {
    Instant start = slotStart();
    AppointmentRecord appt = seedAppointment(start, "booked");

    assertThatThrownBy(() ->
            checkInAt(start.minus(60, ChronoUnit.MINUTES).minusSeconds(1), appt.id(), 1, "nurse-1"))
        .isInstanceOf(ShpException.class)
        .satisfies(e -> {
          var info = ((ShpException) e).info();
          assertThat(info.code()).isEqualTo("checkin-out-of-window");
          assertThat(info.status()).isEqualTo(422);
        });

    assertThatThrownBy(() ->
            checkInAt(start.plus(30, ChronoUnit.MINUTES).plusSeconds(1), appt.id(), 1, "nurse-1"))
        .isInstanceOf(ShpException.class)
        .satisfies(e -> {
          var info = ((ShpException) e).info();
          assertThat(info.code()).isEqualTo("checkin-out-of-window");
          assertThat(info.status()).isEqualTo(422);
        });

    assertThat(
            jdbc.sql("SELECT status FROM scheduling.appointment WHERE id = ?")
                .param(appt.id())
                .query(String.class)
                .single())
        .isEqualTo("booked");
    assertThat(count("SELECT count(*) FROM scheduling.outbox")).isZero();
  }

  @Test
  @DisplayName("non-booked appointments return 409 invalid-state-transition and emit nothing")
  void nonBookedStatusesReturn409InvalidStateTransition() {
    for (String status : List.of("arrived", "cancelled", "fulfilled", "noshow")) {
      Instant start = slotStart();
      AppointmentRecord appt = seedAppointment(start, status);

      assertThatThrownBy(() -> checkInAt(start, appt.id(), 1, "nurse-1"))
          .isInstanceOf(ShpException.class)
          .satisfies(e -> {
            var info = ((ShpException) e).info();
            assertThat(info.code()).isEqualTo("invalid-state-transition");
            assertThat(info.status()).isEqualTo(409);
          });
    }
    assertThat(count("SELECT count(*) FROM scheduling.outbox")).isZero();
  }

  @Test
  @DisplayName("wrong version throws 412 version-conflict and unknown id throws 404 not-found")
  void staleVersionReturns412AndMissingReturns404() {
    Instant start = slotStart();
    AppointmentRecord appt = seedAppointment(start, "booked");

    assertThatThrownBy(() -> checkInAt(start, appt.id(), 99, "nurse-1"))
        .isInstanceOf(ShpException.class)
        .satisfies(e -> {
          var info = ((ShpException) e).info();
          assertThat(info.code()).isEqualTo("version-conflict");
          assertThat(info.status()).isEqualTo(412);
        });

    assertThatThrownBy(() -> checkInAt(start, UUID.randomUUID(), 1, "nurse-1"))
        .isInstanceOf(ShpException.class)
        .satisfies(e -> {
          var info = ((ShpException) e).info();
          assertThat(info.code()).isEqualTo("not-found");
          assertThat(info.status()).isEqualTo(404);
        });
  }
}
