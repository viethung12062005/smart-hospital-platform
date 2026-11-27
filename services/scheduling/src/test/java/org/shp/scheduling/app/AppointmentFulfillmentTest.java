package org.shp.scheduling.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.domain.BookingCommand;
import org.shp.web.idempotency.IdempotencyKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * SCH-25 (US-022): the E6 {@code encounter-finished} consumer on real PostgreSQL 16 - {@code
 * arrived → fulfilled} with history and an E5 outbox event; duplicate and stale events change
 * nothing.
 */
@SpringBootTest(
    properties = {
      "shp.auth.authority=https://idp.test/realms/shp",
      "shp.auth.audience=shp-api",
      "shp.events.validate-schema=true"
    })
@Testcontainers
class AppointmentFulfillmentTest {
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

  private static final String CHANGED = "shp.scheduling.appointment-status-changed.v1";

  @Autowired BookingService booking;
  @Autowired JdbcClient jdbc;
  @Autowired java.time.Clock clock;

  @Autowired
  @Qualifier("encounterEvents")
  Consumer<Message<byte[]>> consumer;

  private BookingTestSupport data;
  private UUID doctor;
  private UUID patient;
  private UUID appointment;

  @BeforeEach
  void seed() {
    data = new BookingTestSupport(jdbc);
    data.clean();
    jdbc.sql("DELETE FROM inbox").update();
    doctor = UUID.randomUUID();
    patient = UUID.randomUUID();
    UUID schedule = data.schedule(doctor, "cardiology");
    Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    UUID slot = data.slot(schedule, doctor, "cardiology", "free", start);
    byte[] hash = IdempotencyKeys.hashBody("{}".getBytes(StandardCharsets.UTF_8));
    appointment =
        booking
            .book(
                new BookingCommand(
                    "sub-" + patient, "key-00000001", hash, slot, patient, "web", null))
            .appointment()
            .id();
    jdbc.sql("DELETE FROM outbox").update();
  }

  private void status(String status) {
    jdbc.sql(
            "UPDATE scheduling.appointment SET status = ?, cancel_reason = CASE WHEN ? = 'cancelled' THEN 'x' END WHERE id = ?")
        .params(status, status, appointment)
        .update();
  }

  private Message<byte[]> finished(UUID appointmentId) {
    Map<String, Object> d =
        Map.of(
            "encounterId", UUID.randomUUID().toString(),
            "appointmentId", appointmentId.toString(),
            "patientId", patient.toString(),
            "practitionerId", doctor.toString(),
            "finishedAt", clock.instant().toString(),
            "version", 1);
    CloudEvent e =
        CloudEvents.create(
            "clinical",
            "shp.clinical.encounter-finished.v1",
            "Encounter/" + UUID.randomUUID(),
            d,
            clock,
            null);
    return MessageBuilder.withPayload(CloudEvents.toJson(e).getBytes(StandardCharsets.UTF_8))
        .build();
  }

  private String one(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(String.class).single();
  }

  private int count(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
  }

  @Test
  @DisplayName("arrived -> fulfilled: version 2, history row, FHIR status and one E5 event")
  void fulfillsAnArrivedAppointment() {
    status("arrived");

    consumer.accept(finished(appointment));

    assertThat(
            one(
                "SELECT status || ':' || version_id FROM scheduling.appointment WHERE id = ?",
                appointment))
        .isEqualTo("fulfilled:2");
    var fhir =
        FhirJson.parse(
            one("SELECT content::text FROM scheduling.appointment WHERE id = ?", appointment),
            org.hl7.fhir.r4.model.Appointment.class);
    assertThat(fhir.getStatus())
        .isEqualTo(org.hl7.fhir.r4.model.Appointment.AppointmentStatus.FULFILLED);
    assertThat(fhir.getMeta().getVersionId()).isEqualTo("2");
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.resource_history WHERE resource_type = 'Appointment' AND version_id = 2 AND operation = 'update'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM outbox WHERE event_type = '" + CHANGED + "'"))
        .isEqualTo(1);
    CloudEvent out =
        CloudEvents.parse(one("SELECT payload::text FROM outbox WHERE event_type = ?", CHANGED));
    assertThat(EventSchemas.validate(out)).isEmpty();
    assertThat(out.data().get("previousStatus").asString()).isEqualTo("arrived");
    assertThat(out.data().get("newStatus").asString()).isEqualTo("fulfilled");
    assertThat(out.data().get("version").asInt()).isEqualTo(2);
  }

  @Test
  @DisplayName("a redelivered event does not change state or emit a second E5")
  void duplicateDeliveryIsIgnored() {
    status("arrived");
    Message<byte[]> msg = finished(appointment);

    consumer.accept(msg);
    consumer.accept(msg);

    assertThat(one("SELECT version_id::text FROM scheduling.appointment WHERE id = ?", appointment))
        .isEqualTo("2");
    assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM inbox WHERE consumer = 'scheduling'")).isEqualTo(1);
  }

  @Test
  @DisplayName("a second encounter-finished for an already fulfilled appointment is stale")
  void lateEventForFulfilledIsIgnored() {
    status("arrived");
    consumer.accept(finished(appointment));

    consumer.accept(finished(appointment));

    assertThat(
            one(
                "SELECT status || ':' || version_id FROM scheduling.appointment WHERE id = ?",
                appointment))
        .isEqualTo("fulfilled:2");
    assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM scheduling.resource_history WHERE version_id = 2"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("booked, cancelled, noshow and unknown appointments are left untouched")
  void otherStatesAreIgnored() {
    for (String s : new String[] {"booked", "cancelled", "noshow"}) {
      status(s);
      consumer.accept(finished(appointment));
      assertThat(
              one(
                  "SELECT status || ':' || version_id FROM scheduling.appointment WHERE id = ?",
                  appointment))
          .isEqualTo(s + ":1");
    }
    consumer.accept(finished(UUID.randomUUID()));
    assertThat(count("SELECT count(*) FROM outbox")).isZero();
    assertThat(count("SELECT count(*) FROM scheduling.resource_history WHERE version_id = 2"))
        .isZero();
  }
}
