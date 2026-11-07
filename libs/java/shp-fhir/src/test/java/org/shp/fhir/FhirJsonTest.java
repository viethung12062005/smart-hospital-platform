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

/** Parse/serialize FHIR R4 JSON cho mọi resource trong phạm vi (01 §4, SHR-01); dữ liệu giả. */
class FhirJsonTest {

  /** Mỗi resource type trong 01 §1 có ít nhất một fixture (src/test/resources/fhir). */
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
              // Không chuyển thông điệp của HAPI (có thể chứa giá trị gửi lên) cho client.
              assertThat(e.detail()).isNull();
            });
  }

  // ---------- vòng khứ hồi ----------
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
    // 37.20 khác 37.2 về độ chính xác đo (FHIR decimal giữ nguyên chữ số).
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
        "{\"resourceType\":\"Observation\",\"status\":\"final\"}"
      })
  void invalidPatientIsValidationFailed(String json) {
    assertValidationFailed(() -> FhirJson.parse(json, Patient.class));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"{\"id\":\"x\"}", "{\"resourceType\":\"Spaceship\"}", "{\"resourceType\":42}"})
  void untypedParseRejectsMissingOrUnknownResourceType(String json) {
    assertValidationFailed(() -> FhirJson.parse(json));
  }
}
