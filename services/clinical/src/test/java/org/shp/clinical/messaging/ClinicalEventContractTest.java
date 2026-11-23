package org.shp.clinical.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shp.clinical.app.CareRelationshipService;
import org.shp.clinical.app.ConditionServiceTest;
import org.shp.clinical.app.EncounterClosing;
import org.shp.clinical.app.EncounterIntake;
import org.shp.clinical.app.EncounterServiceTest;
import org.shp.clinical.messaging.CareRelationshipConsumerTest.MemoryInbox;
import org.shp.clinical.messaging.CareRelationshipConsumerTest.MemoryStore;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.shp.events.Uuids;
import org.shp.messaging.EventConsumers;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.node.ObjectNode;

/**
 * CLN-27 (13 §1, §2): the clinical side of the event contracts E1–E7. Consumer: every producer
 * example clinical subscribes to (E1–E5) is accepted by the real consumer and changes state;
 * unknown fields within the same version are tolerated. Producer: the E6 clinical emits on {@code
 * $finish} validates against the published schema and carries what the example carries. E7
 * (patient-merged) is produced by CLN-09, not built yet: only its example is checked here.
 */
class ClinicalEventContractTest {
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-03T02:30:00Z"), ZoneOffset.UTC);
  static final List<String> CONSUMED =
      List.of(
          "appointment-booked",
          "appointment-cancelled",
          "appointment-rescheduled",
          "appointment-checked-in",
          "appointment-status-changed");

  final MemoryStore care = new MemoryStore();
  final MemoryInbox inbox = new MemoryInbox();
  final EncounterServiceTest.MemoryEncounters encounters =
      new EncounterServiceTest.MemoryEncounters();
  final Consumer<org.springframework.messaging.Message<byte[]>> consumer =
      EventConsumers.idempotent(
          CareRelationshipService.CONSUMER,
          AppointmentConsumerConfig.types(),
          inbox,
          AppointmentConsumerConfig.dispatch(
              new CareRelationshipService(care), new EncounterIntake(encounters, care, CLOCK)));

  // ---------- consumer: E1–E5 ----------

  @Test
  void clinicalSubscribesToExactlyE1ToE5() {
    assertThat(AppointmentConsumerConfig.types())
        .containsExactlyInAnyOrderElementsOf(
            CONSUMED.stream().map(e -> "shp.scheduling." + e + ".v1").toList());
    assertThat(EventSchemas.knownTypes()).containsAll(AppointmentConsumerConfig.types());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "appointment-booked",
        "appointment-cancelled",
        "appointment-rescheduled",
        "appointment-checked-in",
        "appointment-status-changed"
      })
  void everyProducerExampleIsValidAndAccepted(String event) throws IOException {
    CloudEvent e = event("scheduling", event, example("scheduling", event));
    assertThat(EventSchemas.validate(e)).as(event).isEmpty();
    deliver(e);
    assertThat(inbox.handled).containsExactly("shp.scheduling." + event + ".v1");
  }

  @Test
  void theExamplesInOrderBuildCareAndAnEncounter() throws IOException {
    for (String event : CONSUMED) {
      deliver(event("scheduling", event, example("scheduling", event)));
    }
    assertThat(inbox.handled).hasSize(5);
    assertThat(encounters.rows).hasSize(1); // E4
    assertThat(care.rows).isNotEmpty(); // E1, E3, E4
  }

  @Test
  void anUnknownOptionalFieldIsTolerated() throws IOException {
    ObjectNode data = example("scheduling", "appointment-booked");
    data.put("futureOptionalField", "ignored");
    CloudEvent e = event("scheduling", "appointment-booked", data);
    deliver(e);
    assertThat(inbox.handled).hasSize(1);
  }

  // ---------- producer: E6 ----------

  @Test
  void theE6ClinicalEmitsMatchesTheSchemaAndTheExample() throws IOException {
    ObjectNode example = example("clinical", "encounter-finished");
    String enc = example.get("encounterId").asString();
    encounters.arrived(
        enc,
        example.get("patientId").asString(),
        "in-progress",
        Instant.parse("2026-12-03T01:15:00Z"));
    Document search = encounters.rows.get(enc).search();
    search.put("appointmentId", example.get("appointmentId").asString());
    search.put("practitionerId", example.get("practitionerId").asString());
    var conditions = new ConditionServiceTest.MemoryConditions();
    conditions.create(
        "c1",
        example.get("patientId").asString(),
        new Document("resourceType", "Condition"),
        new Document("encounterId", enc)
            .append("category", "encounter-diagnosis")
            .append("verificationStatus", "confirmed")
            .append("recordedDate", java.util.Date.from(CLOCK.instant())),
        "User/doc");

    new EncounterClosing(encounters, conditions, CLOCK)
        .finish(enc, example.get("practitionerId").asString(), "User/doc");

    CloudEvent produced = encounters.events.get(0);
    assertThat(produced.type()).isEqualTo(EncounterClosing.FINISHED_TYPE);
    assertThat(produced.source()).isEqualTo("/services/clinical");
    assertThat(EventSchemas.validate(produced)).isEmpty();
    // same fields as the published example (consumers are tested against it)
    List<String> producedFields = fields(produced.data());
    assertThat(producedFields).containsExactlyInAnyOrderElementsOf(fields(example));
    for (String id : List.of("encounterId", "appointmentId", "patientId", "practitionerId")) {
      assertThat(produced.data().get(id).asString()).isEqualTo(example.get(id).asString());
    }
  }

  @Test
  void theE7ExampleIsValidUntilClinicalProducesIt() throws IOException {
    CloudEvent e = event("clinical", "patient-merged", example("clinical", "patient-merged"));
    assertThat(EventSchemas.validate(e)).isEmpty();
  }

  // ---------- helpers ----------

  private static List<String> fields(tools.jackson.databind.JsonNode node) {
    List<String> names = new java.util.ArrayList<>();
    node.propertyNames().forEach(names::add);
    return names;
  }

  private void deliver(CloudEvent e) {
    byte[] body = CloudEvents.JSON.writeValueAsString(e).getBytes(StandardCharsets.UTF_8);
    consumer.accept(MessageBuilder.withPayload(body).build());
  }

  static CloudEvent event(String context, String event, ObjectNode data) {
    String subjectId =
        data.has("appointmentId")
            ? data.get("appointmentId").asString()
            : data.has("newAppointmentId")
                ? data.get("newAppointmentId").asString()
                : data.has("targetPatientId") ? data.get("targetPatientId").asString() : "x";
    String type = "Appointment";
    if ("patient-merged".equals(event)) {
      type = "Patient";
    }
    return CloudEvents.create(
        context,
        "shp." + context + "." + event + ".v1",
        type + "/" + subjectId,
        data,
        CLOCK,
        Uuids.v7(CLOCK));
  }

  /** The producer's contract example (contracts/events/{context}). */
  static ObjectNode example(String context, String event) throws IOException {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("contracts/events"))) {
      root = root.getParent();
    }
    if (root == null) {
      throw new IllegalStateException("Could not find the repository root");
    }
    Path file =
        root.resolve("contracts/events").resolve(context).resolve(event + ".v1.example.json");
    return (ObjectNode) CloudEvents.JSON.readTree(Files.readString(file));
  }
}
