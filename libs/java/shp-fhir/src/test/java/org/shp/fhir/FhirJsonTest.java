package org.shp.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Schedule;
import org.hl7.fhir.r4.model.Slot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.shp.events.CloudEvents;
import org.shp.web.errors.ShpException;

/** Parse/serialize FHIR R4 JSON for every in-scope resource (01 §4, SHR-01); synthetic data. */
class FhirJsonTest {

  /** Every resource type in 01 §1 has at least one fixture (src/test/resources/fhir). */
  static Stream<Arguments> fixtures() {
    return Stream.of(
        Arguments.of("Patient.json", Patient.class),
        Arguments.of("Practitioner.json", Practitioner.class),
        Arguments.of("PractitionerRole.json", PractitionerRole.class),
        Arguments.of("Schedule.json", Schedule.class),
        Arguments.of("Slot.json", Slot.class),
        Arguments.of("Appointment.json", Appointment.class),
        Arguments.of("Encounter.json", Encounter.class),
        Arguments.of("Observation-blood-pressure.json", Observation.class),
        Arguments.of("Observation-temperature.json", Observation.class),
        Arguments.of("Condition.json", Condition.class),
        Arguments.of("AllergyIntolerance.json", AllergyIntolerance.class),
        Arguments.of("DocumentReference.json", DocumentReference.class));
  }

  static String fixture(String file) {
    try (InputStream in = FhirJsonTest.class.getResourceAsStream("/fhir/" + file)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void assertValidationFailed(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("validation-failed");
              assertThat(e.info().status()).isEqualTo(400);
              // Do not pass HAPI messages (which may contain submitted values) to the client.
              assertThat(e.detail()).isNull();
            });
  }

  // ---------- round trip ----------
  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void roundTripKeepsEveryElement(String file, Class<? extends Resource> type) {
    String json = fixture(file);
    Resource parsed = FhirJson.parse(json, type);
    assertThat(parsed).isInstanceOf(type);

    String out = FhirJson.serialize(parsed);
    assertThat(CloudEvents.JSON.readTree(out)).isEqualTo(CloudEvents.JSON.readTree(json));
    assertThat(FhirJson.parse(out, type).equalsDeep(parsed)).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  void untypedParsePicksTheTypeFromResourceType(String file, Class<? extends Resource> type) {
    assertThat(FhirJson.parse(fixture(file))).isInstanceOf(type);
  }

  @Test
  void idAndVersionComeFromTheDocument() {
    Patient p = FhirJson.parse(fixture("Patient.json"), Patient.class);
    assertThat(p.getIdElement().getIdPart()).isEqualTo("0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01");
    assertThat(p.getMeta().getVersionId()).isEqualTo("3");
    assertThat(p.getNameFirstRep().getText()).isEqualTo("Nguyễn Văn An (test)");
  }

  @Test
  void decimalPrecisionIsKept() {
    // 37.20 differs from 37.2 in measured precision (FHIR decimal preserves digits).
    String out =
        FhirJson.serialize(
            FhirJson.parse(fixture("Observation-temperature.json"), Observation.class));
    assertThat(out).contains("\"value\":37.20");
  }

  @Test
  void outputIsCompactJson() {
    String out = FhirJson.serialize(FhirJson.parse(fixture("Slot.json"), Slot.class));
    assertThat(out).startsWith("{\"resourceType\":\"Slot\"").doesNotContain("\n");
    assertThat(FhirJson.MEDIA_TYPE).isEqualTo("application/fhir+json");
    assertThat(FhirJson.context().getVersion().getVersion().getFhirVersionString())
        .startsWith("4.0");
  }

  // ---------- parse sai → 400 validation-failed ----------
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "{not json",
        "[]",
        "{\"resourceType\":\"Patient\",\"foo\":1}",
        "{\"resourceType\":\"Patient\",\"birthDate\":\"12/04/1985\"}",
        "{\"resourceType\":\"Patient\",\"gender\":\"nam\"}",
        "{\"resourceType\":\"Patient\",\"active\":\"yes\"}",
        "{\"resourceType\":\"Observation\",\"status\":\"final\"}",
        // duplicate keys: HAPI keeps the later value, another reader may keep the earlier one
        "{\"resourceType\":\"Patient\",\"active\":true,\"active\":false}",
        "{\"resourceType\":\"Patient\",\"active\":null}",
        "{\"resourceType\":\"Patient\",\"name\":[{\"family\":null}]}",
        // id invalid as an FHIR id: forbidden characters, longer than 64 characters, not a string;
        // nested resources too
        "{\"resourceType\":\"Patient\",\"id\":\"a b/../c\"}",
        "{\"resourceType\":\"Patient\",\"id\":\"%s\"}",
        "{\"resourceType\":\"Patient\",\"id\":42}",
        "{\"resourceType\":\"Patient\",\"contained\":[{\"resourceType\":\"Practitioner\",\"id\":\"../x\"}]}"
      })
  void invalidPatientIsValidationFailed(String json) {
    String body = json == null ? null : json.replace("%s", "x".repeat(65));
    assertValidationFailed(() -> FhirJson.parse(body, Patient.class));
  }

  @Test
  void validIdsAndNullsInsideArraysAreAccepted() {
    // 64 characters, with '-' and '.'; a null in an array is valid when aligning with _given
    // (extension)
    String id = "a-b.c" + "x".repeat(59);
    Patient p =
        FhirJson.parse(
            "{\"resourceType\":\"Patient\",\"id\":\""
                + id
                + "\",\"name\":[{\"given\":[\"An\",null],\"_given\":[null,{\"extension\":"
                + "[{\"url\":\"https://shp.example.org/x\",\"valueString\":\"y\"}]}]}],"
                + "\"contained\":[{\"resourceType\":\"Practitioner\",\"id\":\"p1\"}]}",
            Patient.class);
    assertThat(p.getIdElement().getIdPart()).isEqualTo(id);
  }

  @Test
  void versionedReferencesSurviveSerialization() {
    // HAPI strips "_history/2" on write by default; 07 §3 and Location use versioned references
    String json =
        "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"x\"},"
            + "\"derivedFrom\":[{\"reference\":\"DocumentReference/abc/_history/2\"}]}";
    assertThat(FhirJson.serialize(FhirJson.parse(json, Observation.class)))
        .contains("\"DocumentReference/abc/_history/2\"");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"{\"id\":\"x\"}", "{\"resourceType\":\"Spaceship\"}", "{\"resourceType\":42}"})
  void untypedParseRejectsMissingOrUnknownResourceType(String json) {
    assertValidationFailed(() -> FhirJson.parse(json));
  }
}
