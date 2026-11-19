package org.shp.clinical.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.clinical.app.CareRelationshipService;
import org.shp.clinical.app.EncounterIntake;
import org.shp.clinical.app.Encounters;
import org.shp.clinical.domain.CareRelationship;
import org.shp.clinical.messaging.CareRelationshipConsumerTest.MemoryInbox;
import org.shp.clinical.messaging.CareRelationshipConsumerTest.MemoryStore;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.node.ObjectNode;

/**
 * CLN-14 (US-023; 13 §3 E4; 01 §4.6; 03 §2.5): the check-in event creates exactly one {@code
 * arrived} Encounter and an {@code encounter} care source. Real consumer, contract example, fake
 * data only.
 */
class EncounterIntakeConsumerTest {
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-03T01:16:00Z"), ZoneOffset.UTC);
  // ids of contracts/events/scheduling/appointment-checked-in.v1.example.json
  static final String APPT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
  static final String PRAC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03";
  static final Instant CHECKED_IN = Instant.parse("2026-12-03T01:15:00Z");

  record Created(String id, String patientId, Document resource, Document search, String by) {}

  /** In-memory {@code encounters} with the unique {@code search.appointmentId} rule. */
  static final class MemoryEncounters implements Encounters {
    final List<Created> rows = new ArrayList<>();

    @Override
    public boolean existsForAppointment(String appointmentId) {
      return rows.stream().anyMatch(r -> appointmentId.equals(r.search().get("appointmentId")));
    }

    @Override
    public void create(
        String id, String patientId, Document resource, Document search, String changedBy) {
      if (existsForAppointment(search.getString("appointmentId"))) {
        throw new IllegalStateException("E11000 duplicate key: search.appointmentId");
      }
      rows.add(new Created(id, patientId, resource, search, changedBy));
    }
  }

  final MemoryEncounters encounters = new MemoryEncounters();
  final MemoryStore care = new MemoryStore();
  final MemoryInbox inbox = new MemoryInbox();
  final Consumer<Message<byte[]>> consumer =
      org.shp.messaging.EventConsumers.idempotent(
          CareRelationshipService.CONSUMER,
          AppointmentConsumerConfig.types(),
          inbox,
          AppointmentConsumerConfig.dispatch(
              new CareRelationshipService(care), new EncounterIntake(encounters, care, CLOCK)));

  @Test
  void checkInCreatesAnArrivedEncounter() throws IOException {
    deliver(checkedIn());
    assertThat(encounters.rows).hasSize(1);
    Created e = encounters.rows.get(0);
    assertThat(e.patientId()).isEqualTo(PAT);
    assertThat(e.by()).isEqualTo("system/appointment-checked-in");
    assertThat(e.id()).hasSize(36).isEqualTo(e.resource().getString("id"));

    String json = e.resource().toJson();
    var r = CloudEvents.JSON.readTree(json);
    assertThat(r.get("resourceType").asString()).isEqualTo("Encounter");
    assertThat(r.get("status").asString()).isEqualTo("arrived");
    assertThat(r.at("/class/code").asString()).isEqualTo("AMB");
    assertThat(r.at("/subject/reference").asString()).isEqualTo("Patient/" + PAT);
    assertThat(r.at("/participant/0/individual/reference").asString())
        .isEqualTo("Practitioner/" + PRAC);
    assertThat(r.at("/participant/0/type/0/coding/0/code").asString()).isEqualTo("ATND");
    assertThat(r.at("/appointment/0/reference").asString()).isEqualTo("Appointment/" + APPT);
    assertThat(r.at("/serviceType/coding/0/code").asString()).isEqualTo("cardiology");
    assertThat(r.at("/period/start").asString()).isEqualTo(CHECKED_IN.toString());
    assertThat(r.has("period") && r.get("period").has("end")).isFalse();

    assertThat(e.search())
        .containsEntry("status", "arrived")
        .containsEntry("appointmentId", APPT)
        .containsEntry("practitionerId", PRAC)
        .containsEntry("serviceType", "cardiology")
        .containsEntry("periodStart", Date.from(CHECKED_IN));
  }

  @Test
  void receivingTheSameMessageTwiceCreatesOneEncounter() throws IOException {
    CloudEvent e = checkedIn();
    deliver(e);
    deliver(e);
    assertThat(encounters.rows).hasSize(1);
  }

  @Test
  void aRepublishedCheckInCreatesNoSecondEncounter() throws IOException {
    deliver(checkedIn());
    deliver(checkedIn()); // new CloudEvents id, same appointment
    assertThat(inbox.handled).hasSize(2);
    assertThat(encounters.rows).hasSize(1);
    assertThat(care.saves).isEqualTo(1);
  }

  @Test
  void checkInGivesTheAttenderAnEncounterCareSource() throws IOException {
    deliver(checkedIn());
    var row = care.find(PRAC, PAT).orElseThrow();
    String encounterId = encounters.rows.get(0).id();
    assertThat(row.source(CareRelationship.ENCOUNTER, encounterId))
        .get()
        .satisfies(s -> {
          assertThat(s.status()).isEqualTo("arrived");
          assertThat(s.at()).isEqualTo(CHECKED_IN);
        });
    assertThat(row.validUntil()).contains(CHECKED_IN.plus(CareRelationship.VALIDITY));
  }

  @Test
  void theEncounterSourceIsAddedToAnExistingRelationship() throws IOException {
    deliver(CareRelationshipConsumerTest.event("appointment-booked", bookedExample()));
    deliver(checkedIn());
    var row = care.find(PRAC, PAT).orElseThrow();
    assertThat(row.sources())
        .extracting(CareRelationship.Source::type)
        .containsExactlyInAnyOrder("appointment", "encounter");
  }

  @Test
  void careEventsStillReachTheCareProjection() throws IOException {
    deliver(CareRelationshipConsumerTest.event("appointment-booked", bookedExample()));
    assertThat(encounters.rows).isEmpty();
    assertThat(care.find(PRAC, PAT)).isPresent();
  }

  private ObjectNode bookedExample() throws IOException {
    ObjectNode data = CareRelationshipConsumerTest.example("appointment-booked");
    data.put("practitionerId", PRAC);
    data.put("patientId", PAT);
    return data;
  }

  private static CloudEvent checkedIn() throws IOException {
    return CareRelationshipConsumerTest.event(
        "appointment-checked-in", CareRelationshipConsumerTest.example("appointment-checked-in"));
  }

  private void deliver(CloudEvent e) {
    byte[] body = CloudEvents.JSON.writeValueAsString(e).getBytes(StandardCharsets.UTF_8);
    consumer.accept(MessageBuilder.withPayload(body).build());
  }
}
