package org.shp.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EventTest {
  static final String SUBJECT = "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70";

  static Path repo(String... parts) {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("contracts/events"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("Không tìm thấy thư mục gốc repo");
    }
    return dir.resolve(Path.of("", parts));
  }

  static Stream<Path> exampleFiles() throws IOException {
    try (var s = Files.walk(repo("contracts", "events"))) {
      return s.filter(p -> p.toString().endsWith(".example.json")).sorted().toList().stream();
    }
  }

  static String typeOf(Path example) {
    return "shp."
        + example.getParent().getFileName()
        + "."
        + example.getFileName().toString().replace(".example.json", "");
  }

  @ParameterizedTest
  @MethodSource("exampleFiles")
  void everyContractExampleProducesAValidEvent(Path file) throws IOException {
    var data = CloudEvents.JSON.readTree(Files.readString(file));
    var evt =
        CloudEvents.create(
            "test", typeOf(file), "Thing/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70", data);
    assertThat(EventSchemas.validate(evt)).isEmpty();
  }

  @Test
  void everyEventInTheCatalogHasAnExampleAndViceVersa() throws IOException {
    List<String> fromExamples = exampleFiles().map(EventTest::typeOf).sorted().toList();
    assertThat(fromExamples)
        .hasSize(11)
        .containsExactlyElementsOf(EventSchemas.knownTypes().stream().sorted().toList());
  }

  @Test
  void missingRequiredFieldIsReported() {
    var evt =
        CloudEvents.create(
            "scheduling",
            "shp.scheduling.appointment-booked.v1",
            SUBJECT,
            Map.of("appointmentId", Uuids.v7(), "version", 1));
    assertThat(EventSchemas.validate(evt)).isNotEmpty().anyMatch(e -> e.contains("patientId"));
  }

  @Test
  void wrongFormatIsReported() {
    var evt =
        CloudEvents.create(
            "scheduling",
            "shp.scheduling.appointment-checked-in.v1",
            SUBJECT,
            Map.of(
                "appointmentId",
                "not-a-uuid",
                "version",
                1,
                "patientId",
                Uuids.v7(),
                "practitionerId",
                Uuids.v7(),
                "specialty",
                "cardiology",
                "checkedInAt",
                "yesterday"));
    assertThat(EventSchemas.validate(evt)).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void unknownEventTypeIsAnError() {
    var evt = CloudEvents.create("x", "shp.nothing.happened.v1", SUBJECT, Map.of());
    assertThat(EventSchemas.validate(evt)).anyMatch(e -> e.contains("chưa có schema"));
  }

  @Test
  void badEnvelopeIsReported() {
    var evt =
        CloudEvents.create(
            "scheduling", "shp.scheduling.appointment-booked.v1", "not-a-subject", Map.of());
    assertThat(EventSchemas.validate(evt)).anyMatch(e -> e.startsWith("envelope"));
  }

  @Test
  void idIsUuidV7TimeIsUtcAndDataschemaDerived() {
    var clock = Clock.fixed(Instant.parse("2026-11-20T02:15:00Z"), ZoneOffset.UTC);
    var evt =
        CloudEvents.create(
            "scheduling", "shp.scheduling.appointment-booked.v1", SUBJECT, Map.of(), clock, null);
    assertThat(UUID.fromString(evt.id()).version()).isEqualTo(7);
    assertThat(evt.time()).isEqualTo(Instant.parse("2026-11-20T02:15:00Z"));
    assertThat(evt.dataSchema())
        .isEqualTo("https://shp.example.org/schemas/scheduling/appointment-booked/v1.json");
    assertThat(evt.source()).isEqualTo("/services/scheduling");
    assertThat(CloudEvents.toJson(evt))
        .contains("\"time\":\"2026-11-20T02:15:00Z\"")
        .doesNotContain("traceparent");
  }

  @Test
  void uuidV7IsTimeOrdered() {
    var a = Uuids.v7(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    var b = Uuids.v7(Clock.fixed(Instant.parse("2026-01-01T00:00:01Z"), ZoneOffset.UTC));
    assertThat(a.toString()).isLessThan(b.toString());
    assertThat(a.variant()).isEqualTo(2);
  }

  @Test
  void traceparentComesFromCurrentSpan() {
    var ctx =
        SpanContext.create(
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "00f067aa0ba902b7",
            TraceFlags.getSampled(),
            TraceState.getDefault());
    var scope = Span.wrap(ctx).makeCurrent();
    try {
      var evt = CloudEvents.create("s", "shp.scheduling.appointment-booked.v1", SUBJECT, Map.of());
      assertThat(evt.traceParent())
          .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    } finally {
      scope.close();
    }
  }

  @Test
  void oversizedEventIsRejected() {
    var big = Map.of("blob", "x".repeat(CloudEvents.MAX_BYTES));
    assertThatThrownBy(
            () -> CloudEvents.create("s", "shp.scheduling.appointment-booked.v1", "A/1", big))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jsonRoundTripPreservesTheEvent() {
    var evt =
        CloudEvents.create("s", "shp.scheduling.appointment-booked.v1", SUBJECT, Map.of("a", 1));
    var back = CloudEvents.parse(CloudEvents.toJson(evt));
    assertThat(back).isEqualTo(evt);
  }

  @Test
  void invalidTypeNameCannotBeCreated() {
    assertThatThrownBy(() -> CloudEvents.create("s", "appointment-booked", "A/1", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
