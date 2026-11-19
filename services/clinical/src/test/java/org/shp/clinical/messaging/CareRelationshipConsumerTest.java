package org.shp.clinical.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.shp.clinical.app.CareRelationshipService;
import org.shp.clinical.app.CareRelationships;
import org.shp.clinical.domain.CareRelationship;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.shp.events.Uuids;
import org.shp.messaging.EventConsumers;
import org.shp.messaging.Inbox;
import org.shp.messaging.PoisonEventException;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.node.ObjectNode;

/**
 * CLN-13 (US-033; 13 §3 E1, E2, E3, E5; 15 §4) through the real consumer: the producer's contract
 * examples, schema validation and the exactly-once inbox, with in-memory storage. Fake data only.
 */
class CareRelationshipConsumerTest {
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T02:15:00Z"), ZoneOffset.UTC);
  static final String PRAC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03";
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
  static final String APPT_1 = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
  static final String APPT_2 = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e05";
  static final Instant START_1 = Instant.parse("2026-12-03T01:15:00Z");
  static final Instant START_2 = Instant.parse("2026-12-10T01:15:00Z");

  /** In-memory {@code care_relationships}, counting writes. */
  static final class MemoryStore implements CareRelationships {
    final Map<String, CareRelationship> rows = new HashMap<>();
    int saves;

    @Override
    public Optional<CareRelationship> find(String practitionerId, String patientId) {
      return Optional.ofNullable(rows.get(practitionerId + "|" + patientId));
    }

    @Override
    public List<CareRelationship> findBySource(String patientId, String type, String sourceId) {
      return rows.values().stream()
          .filter(r -> r.patientId().equals(patientId) && r.source(type, sourceId).isPresent())
          .toList();
    }

    @Override
    public void save(CareRelationship relationship) {
      saves++;
      rows.put(relationship.id(), relationship);
    }

    Optional<Instant> validUntil() {
      return find(PRAC, PAT).flatMap(CareRelationship::validUntil);
    }
  }

  /** In-memory inbox: a message id is handled once per consumer. */
  static final class MemoryInbox implements Inbox {
    final Set<String> seen = new HashSet<>();
    final List<String> handled = new ArrayList<>();

    @Override
    public boolean handleOnce(String consumer, CloudEvent event, Runnable handler) {
      if (!seen.add(consumer + "|" + event.id())) {
        return false;
      }
      handler.run();
      handled.add(event.type());
      return true;
    }
  }

  final MemoryStore store = new MemoryStore();
  final MemoryInbox inbox = new MemoryInbox();
  final Consumer<Message<byte[]>> consumer =
      EventConsumers.idempotent(
          CareRelationshipService.CONSUMER,
          CareRelationshipService.TYPES,
          inbox,
          new CareRelationshipService(store)::handle);

  // ---------- AC 1: booking gives care until start + 365 days ----------

  @Test
  void bookingGivesCareUntilStartPlus365Days() throws IOException {
    deliver(booked(APPT_1, START_1, 1));
    assertThat(store.validUntil()).contains(START_1.plus(CareRelationship.VALIDITY));
    assertThat(store.find(PRAC, PAT).orElseThrow().sources())
        .singleElement()
        .satisfies(s -> {
          assertThat(s.type()).isEqualTo("appointment");
          assertThat(s.id()).isEqualTo(APPT_1);
          assertThat(s.status()).isEqualTo("booked");
        });
  }

  @Test
  void theSameMessageTwiceIsHandledOnce() throws IOException {
    CloudEvent e = booked(APPT_1, START_1, 1);
    deliver(e);
    deliver(e);
    assertThat(inbox.handled).hasSize(1);
    assertThat(store.saves).isEqualTo(1);
  }

  @Test
  void aRepublishedEventWithTheSameVersionChangesNothing() throws IOException {
    deliver(booked(APPT_1, START_1, 1));
    deliver(booked(APPT_1, START_1, 1)); // new CloudEvents id, same appointment version
    assertThat(inbox.handled).hasSize(2);
    assertThat(store.saves).isEqualTo(1);
  }

  // ---------- AC 2: cancelling the last source loses care ----------

  @Test
  void cancellingTheOnlyAppointmentLosesCare() throws IOException {
    deliver(booked(APPT_1, START_1, 1));
    deliver(cancelled(APPT_1, 2));
    assertThat(store.validUntil()).isEmpty();
    assertThat(store.find(PRAC, PAT).orElseThrow().source("appointment", APPT_1))
        .get()
        .extracting(CareRelationship.Source::status)
        .isEqualTo("cancelled");
  }

  @Test
  void anotherSourceKeepsCare() throws IOException {
    deliver(booked(APPT_1, START_1, 1));
    deliver(booked(APPT_2, START_2, 1));
    deliver(cancelled(APPT_2, 2));
    assertThat(store.validUntil()).contains(START_1.plus(CareRelationship.VALIDITY));
  }

  @Test
  void aLateOlderBookingDoesNotRestoreACancelledAppointment() throws IOException {
    deliver(booked(APPT_1, START_1, 1));
    deliver(cancelled(APPT_1, 2));
    deliver(booked(APPT_1, START_1, 1)); // redelivered after the cancel
    assertThat(store.validUntil()).isEmpty();
  }

  @Test
  void cancellingAnUnknownRelationshipDoesNothing() throws IOException {
    deliver(cancelled(APPT_1, 2));
    assertThat(store.rows).isEmpty();
  }

  // ---------- E3: reschedule = cancel old + book new, in one event ----------

  @Test
  void rescheduleMovesCareToTheNewAppointment() throws IOException {
    deliver(booked(APPT_1, START_1, 3));
    deliver(contractEvent("appointment-rescheduled"));
    var row = store.find(PRAC, PAT).orElseThrow();
    assertThat(row.source("appointment", APPT_1).orElseThrow().status()).isEqualTo("cancelled");
    assertThat(row.source("appointment", APPT_2).orElseThrow().status()).isEqualTo("booked");
    // newStart of the contract example
    assertThat(row.validUntil())
        .contains(Instant.parse("2026-12-04T01:15:00Z").plus(CareRelationship.VALIDITY));
  }

  // ---------- E5: no-show ends the source, fulfilled keeps it ----------

  @Test
  void noShowLosesCare() throws IOException {
    deliver(booked(APPT_1, START_1, 1));
    deliver(statusChanged(APPT_1, "noshow", 4));
    assertThat(store.validUntil()).isEmpty();
  }

  @Test
  void fulfilledKeepsCare() throws IOException {
    deliver(booked(APPT_1, START_1, 1));
    deliver(contractEvent("appointment-status-changed")); // fulfilled, version 4
    assertThat(store.validUntil()).contains(START_1.plus(CareRelationship.VALIDITY));
    assertThat(store.find(PRAC, PAT).orElseThrow().source("appointment", APPT_1).orElseThrow())
        .extracting(CareRelationship.Source::status)
        .isEqualTo("fulfilled");
  }

  // ---------- contract ----------

  @Test
  void everyContractExampleIsAccepted() throws IOException {
    for (String event :
        List.of(
            "appointment-booked",
            "appointment-cancelled",
            "appointment-rescheduled",
            "appointment-status-changed")) {
      deliver(contractEvent(event));
    }
    assertThat(inbox.handled).hasSize(4);
  }

  @Test
  void otherEventTypesAreIgnored() throws IOException {
    deliver(contractEvent("appointment-checked-in"));
    assertThat(inbox.handled).isEmpty();
    assertThat(store.rows).isEmpty();
  }

  @Test
  void anEventOutsideTheSchemaIsPoison() throws IOException {
    ObjectNode data = example("appointment-booked");
    data.remove("practitionerId");
    CloudEvent bad = event("appointment-booked", data);
    assertThatThrownBy(() -> deliver(bad)).isInstanceOf(PoisonEventException.class);
    assertThat(store.rows).isEmpty();
  }

  // ---------- helpers ----------

  CloudEvent booked(String appointmentId, Instant start, long version) throws IOException {
    ObjectNode data = example("appointment-booked");
    data.put("appointmentId", appointmentId);
    data.put("practitionerId", PRAC);
    data.put("patientId", PAT);
    data.put("start", start.toString());
    data.put("end", start.plusSeconds(900).toString());
    data.put("version", version);
    return event("appointment-booked", data);
  }

  CloudEvent cancelled(String appointmentId, long version) throws IOException {
    ObjectNode data = example("appointment-cancelled");
    data.put("appointmentId", appointmentId);
    data.put("practitionerId", PRAC);
    data.put("patientId", PAT);
    data.put("version", version);
    return event("appointment-cancelled", data);
  }

  CloudEvent statusChanged(String appointmentId, String status, long version) throws IOException {
    ObjectNode data = example("appointment-status-changed");
    data.put("appointmentId", appointmentId);
    data.put("patientId", PAT);
    data.put("newStatus", status);
    data.put("version", version);
    return event("appointment-status-changed", data);
  }

  CloudEvent contractEvent(String event) throws IOException {
    ObjectNode data = example(event);
    // The examples use these ids for the pair; keep them aligned with the constants above.
    if (data.has("practitionerId")) {
      data.put("practitionerId", PRAC);
    }
    data.put("patientId", PAT);
    return event(event, data);
  }

  void deliver(CloudEvent e) {
    byte[] body = CloudEvents.JSON.writeValueAsString(e).getBytes(StandardCharsets.UTF_8);
    consumer.accept(MessageBuilder.withPayload(body).build());
  }

  static CloudEvent event(String event, ObjectNode data) {
    String id =
        data.has("appointmentId")
            ? data.get("appointmentId").asString()
            : data.get("newAppointmentId").asString();
    CloudEvent evt =
        CloudEvents.create(
            "scheduling",
            "shp.scheduling." + event + ".v1",
            "Appointment/" + id,
            data,
            CLOCK,
            Uuids.v7(CLOCK));
    return evt;
  }

  /** The producer's contract example for {@code event} (contracts/events/scheduling). */
  static ObjectNode example(String event) throws IOException {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("contracts/events"))) {
      root = root.getParent();
    }
    if (root == null) {
      throw new IllegalStateException("Could not find the repository root");
    }
    Path file = root.resolve("contracts/events/scheduling").resolve(event + ".v1.example.json");
    return (ObjectNode) CloudEvents.JSON.readTree(Files.readString(file));
  }

  @Test
  void examplesAreValidAgainstTheEmbeddedSchemas() throws IOException {
    for (String event :
        List.of(
            "appointment-booked",
            "appointment-cancelled",
            "appointment-rescheduled",
            "appointment-status-changed")) {
      assertThat(EventSchemas.validate(contractEvent(event))).as(event).isEmpty();
    }
  }
}
