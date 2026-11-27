package org.shp.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.shp.events.Uuids;
import org.shp.notification.app.ReminderService;
import tools.jackson.databind.node.ObjectNode;

/**
 * NTF-14 (13 §1.1, §5): the notification side of the event contracts, without a database.
 * Notification consumes E1 (appointment-booked) and E10 (no-show-scored) and produces no domain
 * event of its own. The reminder milestones created from the examples are asserted in {@code
 * ReminderServiceTest} and {@code ReminderConsumerIntegrationTest}; here the contract itself is
 * checked: the producer examples validate against the published schemas, the fields the consumer
 * reads are required by them, and a within-version addition does not break validation.
 */
class NotificationEventContractTest {
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T02:00:00Z"), ZoneOffset.UTC);

  @Test
  void notificationConsumesExactlyE1AndE10AndBothHaveASchema() {
    assertThat(List.of(ReminderService.BOOKED_TYPE, ReminderService.NO_SHOW_SCORED_TYPE))
        .containsExactly("shp.scheduling.appointment-booked.v1", "shp.ml.no-show-scored.v1");
    assertThat(EventSchemas.knownTypes())
        .contains(ReminderService.BOOKED_TYPE, ReminderService.NO_SHOW_SCORED_TYPE);
  }

  @ParameterizedTest
  @CsvSource({"scheduling,appointment-booked", "ml,no-show-scored"})
  void theProducerExampleIsValid(String context, String event) throws IOException {
    CloudEvent e = event(context, event, example(context, event));
    assertThat(EventSchemas.validate(e)).as(event).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "scheduling,appointment-booked,appointmentId;patientId;start;bookedAt",
    "ml,no-show-scored,appointmentId;patientId;start;highRisk"
  })
  void theFieldsTheConsumerReadsAreRequiredByTheSchema(String context, String event, String fields)
      throws IOException {
    var schema =
        CloudEvents.JSON.readTree(
            Files.readString(
                root().resolve("contracts/events/%s/%s.v1.json".formatted(context, event))));
    List<String> required = new java.util.ArrayList<>();
    schema.get("required").forEach(n -> required.add(n.asString()));
    assertThat(required).containsAll(List.of(fields.split(";")));
  }

  @ParameterizedTest
  @CsvSource({"scheduling,appointment-booked", "ml,no-show-scored"})
  void anUnknownOptionalFieldWithinTheSameVersionStillValidates(String context, String event)
      throws IOException {
    ObjectNode data = example(context, event);
    data.put("futureOptionalField", "ignored");
    assertThat(EventSchemas.validate(event(context, event, data))).as(event).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"scheduling,appointment-booked,patientId", "ml,no-show-scored,highRisk"})
  void aMissingRequiredFieldIsRejectedByTheSchema(String context, String event, String field)
      throws IOException {
    ObjectNode data = example(context, event);
    data.remove(field);
    assertThat(EventSchemas.validate(event(context, event, data))).as(event).isNotEmpty();
  }

  static CloudEvent event(String context, String event, ObjectNode data) {
    return CloudEvents.create(
        context,
        "shp." + context + "." + event + ".v1",
        "Appointment/" + data.get("appointmentId").asString(),
        data,
        CLOCK,
        Uuids.v7(CLOCK));
  }

  static Path root() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("contracts/events"))) {
      root = root.getParent();
    }
    if (root == null) {
      throw new IllegalStateException("Could not find the repository root");
    }
    return root;
  }

  /** The producer's contract example (contracts/events/{context}). */
  static ObjectNode example(String context, String event) throws IOException {
    Path file =
        root().resolve("contracts/events").resolve(context).resolve(event + ".v1.example.json");
    return (ObjectNode) CloudEvents.JSON.readTree(Files.readString(file));
  }
}
