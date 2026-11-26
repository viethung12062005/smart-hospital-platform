package org.shp.scheduling.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.hl7.fhir.r4.model.Appointment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventPublisher;
import org.shp.fhir.FhirJson;
import org.shp.messaging.PoisonEventException;
import org.shp.scheduling.app.BookingTestSupport;
import org.shp.scheduling.app.PatientMergeService;
import org.shp.scheduling.domain.AppointmentFhir;
import org.shp.scheduling.domain.AppointmentRecord;
import org.shp.scheduling.domain.AppointmentSearchCriteria;
import org.shp.scheduling.infra.AppointmentRepository;
import org.shp.scheduling.infra.AppointmentSearchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-26 (US-004, 13 §3 E7): end-to-end consumer tests for {@code shp.clinical.patient-merged.v1}
 * on real PostgreSQL 16 and RabbitMQ — verifies appointment {@code patientId} replacement, FHIR
 * participant updates, appended {@code resource_history}, and idempotency across replays and
 * reruns.
 */
@SpringBootTest(
    properties = {
      "shp.auth.authority=https://idp.test/realms/shp",
      "shp.auth.audience=shp-api",
      "shp.events.validate-schema=true"
    })
@Testcontainers
class PatientMergedConsumerTest {
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

  @Autowired EventPublisher publisher;
  @Autowired Consumer<Message<byte[]>> clinicalEvents;
  @Autowired AppointmentRepository appointments;
  @Autowired AppointmentSearchRepository searchRepository;
  @Autowired JdbcClient jdbc;

  private BookingTestSupport data;
  private UUID doctor;
  private UUID schedule;
  private int counter;

  @BeforeEach
  void reset() {
    data = new BookingTestSupport(jdbc);
    jdbc.sql(
            "TRUNCATE scheduling.appointment, scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history, scheduling.outbox, scheduling.idempotency_key, scheduling.inbox CASCADE")
        .update();
    doctor = UUID.randomUUID();
    schedule = data.schedule(doctor, "cardiology");
    counter = 0;
  }

  private AppointmentRecord seedAppointment(UUID patientId, String status) {
    Instant start = Instant.parse("2026-12-10T08:00:00Z").plusSeconds(900L * ++counter);
    UUID slotId = data.slot(schedule, doctor, "cardiology", "busy", start);
    UUID apptId = UUID.randomUUID();
    Instant createdAt = start.minus(2, ChronoUnit.DAYS);
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
    AppointmentRecord booked = draft.withContent(AppointmentFhir.booked(draft, "Follow-up"));
    String stored = appointments.insertWithHistory(booked);
    if (!"booked".equals(status)) {
      String cancelReason = "cancelled".equals(status) ? "patient-request" : null;
      jdbc.sql("UPDATE scheduling.appointment SET status = ?, cancel_reason = ? WHERE id = ?")
          .params(status, cancelReason, apptId)
          .update();
    }
    return booked.withContent(stored);
  }

  private static CloudEvent mergedEvent(UUID sourcePatientId, UUID targetPatientId) {
    return CloudEvents.create(
        "clinical",
        PatientMergeService.PATIENT_MERGED_TYPE,
        "Patient/" + targetPatientId,
        Map.of(
            "sourcePatientId", sourcePatientId.toString(),
            "targetPatientId", targetPatientId.toString(),
            "mergedAt", "2026-11-20T02:15:00Z"));
  }

  private static Message<byte[]> message(CloudEvent event) {
    return MessageBuilder.withPayload(CloudEvents.toJson(event).getBytes(StandardCharsets.UTF_8))
        .build();
  }

  private long count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Long.class).single();
  }

  @Test
  @DisplayName(
      "patient-merged delivered over RabbitMQ reassigns source appointments, FHIR participants, and history")
  void patientMergedViaBusReassignsAllAppointmentsAndUpdatesFhirContentAndHistory() {
    UUID sourcePatient = UUID.randomUUID();
    UUID targetPatient = UUID.randomUUID();
    UUID otherPatient = UUID.randomUUID();

    AppointmentRecord srcBooked = seedAppointment(sourcePatient, "booked");
    AppointmentRecord srcArrived = seedAppointment(sourcePatient, "arrived");
    AppointmentRecord srcCancelled = seedAppointment(sourcePatient, "cancelled");
    AppointmentRecord tgtBooked = seedAppointment(targetPatient, "booked");
    AppointmentRecord unrelated = seedAppointment(otherPatient, "booked");

    CloudEvent event = mergedEvent(sourcePatient, targetPatient);
    publisher.publish(event);

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() ->
            count(
                    "SELECT count(*) FROM scheduling.inbox WHERE consumer = ? AND message_id = ?",
                    PatientMergeService.CONSUMER,
                    UUID.fromString(event.id()))
                == 1L);

    assertThat(
            count(
                "SELECT count(*) FROM scheduling.appointment WHERE patient_id = ?", sourcePatient))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.appointment WHERE patient_id = ?", targetPatient))
        .isEqualTo(4);

    var targetRows =
        searchRepository.search(
            new AppointmentSearchCriteria(
                targetPatient, null, Set.of(), null, null, null, false, null, null, 20));
    assertThat(targetRows)
        .extracting(r -> r.id())
        .containsExactlyInAnyOrder(
            srcBooked.id(), srcArrived.id(), srcCancelled.id(), tgtBooked.id());

    for (UUID reassignedId : List.of(srcBooked.id(), srcArrived.id(), srcCancelled.id())) {
      AppointmentRecord updated = appointments.findById(reassignedId).orElseThrow();
      assertThat(updated.patientId()).isEqualTo(targetPatient);
      assertThat(updated.versionId()).isEqualTo(2);

      Appointment fhir = FhirJson.parse(updated.content(), Appointment.class);
      assertThat(fhir.getMeta().getVersionId()).isEqualTo("2");
      assertThat(fhir.getParticipant())
          .extracting(p -> p.getActor().getReference())
          .containsExactlyInAnyOrder("Patient/" + targetPatient, "Practitioner/" + doctor);
      assertThat(fhir.getExtensionByUrl(AppointmentFhir.BOOKING_CHANNEL_URL)).isNotNull();

      assertThat(
              count(
                  "SELECT count(*) FROM scheduling.resource_history WHERE resource_type = 'Appointment' AND id = ? AND version_id = 2 AND operation = 'update' AND changed_by = ?",
                  reassignedId,
                  "system/patient-merged"))
          .isEqualTo(1);
    }

    // Target's existing appointment and unrelated patient's appointment remain untouched at version
    // 1.
    assertThat(appointments.findById(tgtBooked.id()).orElseThrow().versionId()).isEqualTo(1);
    AppointmentRecord otherAfter = appointments.findById(unrelated.id()).orElseThrow();
    assertThat(otherAfter.patientId()).isEqualTo(otherPatient);
    assertThat(otherAfter.versionId()).isEqualTo(1);
  }

  @Test
  @DisplayName("duplicate delivery and rerun with a new event ID produce the exact same state")
  void duplicateEventAndRerunWithNewEventIdProduceSameResult() {
    UUID sourcePatient = UUID.randomUUID();
    UUID targetPatient = UUID.randomUUID();
    AppointmentRecord first = seedAppointment(sourcePatient, "booked");
    AppointmentRecord second = seedAppointment(sourcePatient, "fulfilled");

    CloudEvent firstEvent = mergedEvent(sourcePatient, targetPatient);
    Message<byte[]> firstMsg = message(firstEvent);

    // Same event delivered twice -> PostgresInbox skips duplicate delivery
    clinicalEvents.accept(firstMsg);
    clinicalEvents.accept(firstMsg);

    // Different event ID for the same merge -> 0 source rows left, nothing mutated again
    CloudEvent rerunEvent = mergedEvent(sourcePatient, targetPatient);
    clinicalEvents.accept(message(rerunEvent));

    assertThat(
            count(
                "SELECT count(*) FROM scheduling.inbox WHERE consumer = ? AND message_id IN (?, ?)",
                PatientMergeService.CONSUMER,
                UUID.fromString(firstEvent.id()),
                UUID.fromString(rerunEvent.id())))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.appointment WHERE patient_id = ?", sourcePatient))
        .isZero();

    for (UUID id : List.of(first.id(), second.id())) {
      AppointmentRecord rec = appointments.findById(id).orElseThrow();
      assertThat(rec.patientId()).isEqualTo(targetPatient);
      assertThat(rec.versionId()).isEqualTo(2);
      assertThat(
              count(
                  "SELECT count(*) FROM scheduling.resource_history WHERE resource_type = 'Appointment' AND id = ?",
                  id))
          .isEqualTo(2);
    }
  }

  @Test
  @DisplayName("self-merge and source patient with no appointments succeed as idempotent no-ops")
  void selfMergeAndPatientWithNoAppointmentsAreNoOps() {
    UUID patientWithAppt = UUID.randomUUID();
    AppointmentRecord existing = seedAppointment(patientWithAppt, "booked");
    UUID absentPatient = UUID.randomUUID();
    UUID targetPatient = UUID.randomUUID();

    CloudEvent selfMerge = mergedEvent(patientWithAppt, patientWithAppt);
    CloudEvent emptySourceMerge = mergedEvent(absentPatient, targetPatient);

    clinicalEvents.accept(message(selfMerge));
    clinicalEvents.accept(message(emptySourceMerge));

    assertThat(
            count(
                "SELECT count(*) FROM scheduling.inbox WHERE consumer = ? AND message_id IN (?, ?)",
                PatientMergeService.CONSUMER,
                UUID.fromString(selfMerge.id()),
                UUID.fromString(emptySourceMerge.id())))
        .isEqualTo(2);
    AppointmentRecord untouched = appointments.findById(existing.id()).orElseThrow();
    assertThat(untouched.patientId()).isEqualTo(patientWithAppt);
    assertThat(untouched.versionId()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "schema-invalid E7 throws PoisonEventException and unrelated event types are ignored")
  void schemaInvalidEventThrowsPoisonEventExceptionAndUnrelatedTypeIsIgnored() {
    UUID sourcePatient = UUID.randomUUID();
    UUID targetPatient = UUID.randomUUID();
    AppointmentRecord appt = seedAppointment(sourcePatient, "booked");

    CloudEvent invalid =
        CloudEvents.create(
            "clinical",
            PatientMergeService.PATIENT_MERGED_TYPE,
            "Patient/" + targetPatient,
            Map.of(
                "sourcePatientId", sourcePatient.toString(), "mergedAt", "2026-11-20T02:15:00Z"));
    assertThatThrownBy(() -> clinicalEvents.accept(message(invalid)))
        .isInstanceOf(PoisonEventException.class);

    CloudEvent unrelated =
        CloudEvents.create(
            "scheduling",
            "shp.scheduling.appointment-booked.v1",
            "Appointment/" + appt.id(),
            Map.of("appointmentId", appt.id().toString()));
    clinicalEvents.accept(message(unrelated));

    assertThat(count("SELECT count(*) FROM scheduling.inbox")).isZero();
    assertThat(appointments.findById(appt.id()).orElseThrow().patientId()).isEqualTo(sourcePatient);
  }
}
