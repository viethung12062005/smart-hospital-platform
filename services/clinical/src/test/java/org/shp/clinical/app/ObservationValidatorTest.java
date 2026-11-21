package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.shp.clinical.infra.TerminologyService;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;

/** CLN-16 (US-025; 01 §4.7; 09 C16): Observation validation and interpretation. Fake data only. */
class ObservationValidatorTest {
  static final Set<String> VITAL_CODES =
      Set.of(
          "8867-4", "85354-9", "8480-6", "8462-4", "8310-5", "9279-1", "59408-5", "29463-7",
          "8302-2", "39156-5");

  static TerminologyService vitalSigns() {
    TerminologyService t = mock(TerminologyService.class);
    when(t.isKnown(eq("loinc-vital-signs"), anyString()))
        .thenAnswer(a -> VITAL_CODES.contains(a.getArgument(1, String.class)));
    return t;
  }

  final ObservationValidator validator =
      new ObservationValidator(new ObservationCodeValidator(vitalSigns()));

  /** A vital sign with a single quantity. */
  static String vital(String code, String value, String unit) {
    return """
    {
      "resourceType": "Observation",
      "status": "final",
      "category": [ { "coding": [ { "system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "vital-signs" } ] } ],
      "code": { "coding": [ { "system": "http://loinc.org", "code": "%s" } ] },
      "subject": { "reference": "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f" },
      "encounter": { "reference": "Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e10" },
      "effectiveDateTime": "2026-12-03T01:20:00Z",
      "performer": [ { "reference": "Practitioner/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03" } ],
      "valueQuantity": { "value": %s, "unit": "%s", "system": "http://unitsofmeasure.org", "code": "%s" }
    }
    """
        .formatted(code, value, unit, unit);
  }

  static final String BLOOD_PRESSURE =
      """
      {
        "resourceType": "Observation",
        "status": "final",
        "category": [ { "coding": [ { "system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "vital-signs" } ] } ],
        "code": { "coding": [ { "system": "http://loinc.org", "code": "85354-9" } ] },
        "subject": { "reference": "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f" },
        "encounter": { "reference": "Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e10" },
        "effectiveDateTime": "2026-12-03T01:20:00Z",
        "performer": [ { "reference": "Practitioner/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03" } ],
        "component": [
          { "code": { "coding": [ { "system": "http://loinc.org", "code": "8480-6" } ] },
            "valueQuantity": { "value": SYS, "unit": "mm[Hg]", "system": "http://unitsofmeasure.org", "code": "mm[Hg]" } },
          { "code": { "coding": [ { "system": "http://loinc.org", "code": "8462-4" } ] },
            "valueQuantity": { "value": DIA, "unit": "mm[Hg]", "system": "http://unitsofmeasure.org", "code": "mm[Hg]" } }
        ]
      }
      """;

  static String bloodPressure(String sys, String dia) {
    return BLOOD_PRESSURE.replace("SYS", sys).replace("DIA", dia);
  }

  static String laboratory(String valuePart) {
    return """
    {
      "resourceType": "Observation",
      "status": "final",
      "category": [ { "coding": [ { "system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "laboratory" } ] } ],
      "code": { "coding": [ { "system": "http://loinc.org", "code": "2339-0" } ] },
      "subject": { "reference": "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f" },
      "effectiveDateTime": "2026-12-03T01:20:00Z",
      "performer": [ { "reference": "Practitioner/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03" } ]%s
    }
    """
        .formatted(valuePart);
  }

  Observation validated(String json) {
    Observation o = FhirJson.parse(json, Observation.class);
    validator.validate(o);
    return o;
  }

  static String flag(List<CodeableConcept> interpretation) {
    assertThat(interpretation).hasSize(1);
    var coding = interpretation.get(0).getCodingFirstRep();
    assertThat(coding.getSystem()).isEqualTo(ObservationValidator.INTERPRETATION_SYSTEM);
    return coding.getCode();
  }

  void assertInvalid(String json, String path) {
    assertThatThrownBy(() -> validated(json))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("validation-failed");
              assertThat(e.info().status()).isEqualTo(400);
              assertThat(e.detail()).startsWith(path);
            });
  }

  void assertOutOfRange(String json, String path) {
    assertThatThrownBy(() -> validated(json))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("value-out-of-range");
              assertThat(e.info().status()).isEqualTo(422);
              assertThat(e.detail()).isEqualTo(path + " is outside the plausible range.");
            });
  }

  // ---------- acceptance: temperature 50 °C, SpO2 120 ----------

  @Test
  void temperature50IsValueOutOfRange() {
    assertOutOfRange(vital("8310-5", "50", "Cel"), "Observation.valueQuantity.value");
  }

  /** TC-AC-VT.1: SpO2 = 120 is rejected and names the field. */
  @Test
  void spo2Of120IsValueOutOfRange() {
    assertOutOfRange(vital("59408-5", "120", "%"), "Observation.valueQuantity.value");
  }

  /** The bounds of the SRS business rule BR vital-ranges (UC-04, authoritative). */
  @ParameterizedTest
  @CsvSource({
    "8310-5, 30, Cel",
    "8310-5, 43, Cel",
    "8867-4, 20, /min",
    "8867-4, 250, /min",
    "59408-5, 50, %",
    "59408-5, 100, %",
    "29463-7, 0.5, kg",
    "29463-7, 300, kg",
    "8302-2, 30, cm",
    "8302-2, 250, cm",
  })
  void plausibleBoundsAreAccepted(String code, String value, String unit) {
    assertThatCode(() -> validated(vital(code, value, unit))).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource({
    "8310-5, 29.9, Cel",
    "8310-5, 43.1, Cel",
    "8867-4, 19, /min",
    "8867-4, 251, /min",
    "59408-5, 49, %",
    "29463-7, 0.4, kg",
    "29463-7, 300.1, kg",
    "8302-2, 29, cm",
    "8302-2, 251, cm",
    "9279-1, 0, /min",
    "39156-5, 101, kg/m2",
  })
  void implausibleValuesAreRejected(String code, String value, String unit) {
    assertOutOfRange(vital(code, value, unit), "Observation.valueQuantity.value");
  }

  @ParameterizedTest
  @CsvSource({"50, 30", "260, 160"})
  void bloodPressureSrsBoundsAreAccepted(String sys, String dia) {
    assertThatCode(() -> validated(bloodPressure(sys, dia))).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource({
    "49, 80, systolic",
    "261, 80, systolic",
    "120, 29, diastolic",
    "120, 161, diastolic",
  })
  void bloodPressureOutsideTheSrsRangeIsRejected(String sys, String dia, String component) {
    assertOutOfRange(
        bloodPressure(sys, dia), "Observation.component[" + component + "].valueQuantity.value");
  }

  /**
   * The terminology catalogue and the table in {@link VitalSigns} must agree. A code the catalogue
   * accepts but the table does not know is a request error (400), never a 500.
   */
  @Test
  void aCatalogueCodeMissingFromTheTableIsAValidationError() {
    TerminologyService onlyCatalogue = mock(TerminologyService.class);
    when(onlyCatalogue.isKnown(eq("loinc-vital-signs"), anyString())).thenReturn(true);
    var drifted = new ObservationValidator(new ObservationCodeValidator(onlyCatalogue));

    Observation o = FhirJson.parse(vital("1234-5", "5", "/min"), Observation.class);
    assertThatThrownBy(() -> drifted.validate(o))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("validation-failed");
              assertThat(e.info().status()).isEqualTo(400);
              assertThat(e.detail())
                  .isEqualTo("Observation.code is not a vital sign of the profile.");
            });
  }

  @Test
  void theErrorNeverEchoesTheValue() {
    assertThatThrownBy(() -> validated(vital("8310-5", "51.234", "Cel")))
        .hasMessageNotContaining("51.234");
  }

  // ---------- interpretation ----------

  @ParameterizedTest
  @CsvSource({
    "8310-5, 37.0, Cel, N",
    "8310-5, 38.5, Cel, H",
    "8310-5, 35.5, Cel, L",
    "8867-4, 120, /min, H",
    "59408-5, 91, %, L",
    "59408-5, 97, %, N",
    "39156-5, 27, kg/m2, H",
  })
  void interpretationComesFromTheAdultRange(String code, String value, String unit, String flag) {
    assertThat(flag(validated(vital(code, value, unit)).getInterpretation())).isEqualTo(flag);
  }

  @Test
  void weightAndHeightHaveNoInterpretation() {
    assertThat(validated(vital("29463-7", "70", "kg")).getInterpretation()).isEmpty();
    assertThat(validated(vital("8302-2", "170", "cm")).getInterpretation()).isEmpty();
  }

  @Test
  void theResourceReferenceRangeWins() {
    String json =
        vital("8867-4", "105", "/min")
            .replace(
                "\"valueQuantity\"",
                "\"referenceRange\": [ { \"low\": { \"value\": 50 }, \"high\": { \"value\": 110 } } ],"
                    + " \"valueQuantity\"");
    assertThat(flag(validated(json).getInterpretation())).isEqualTo("N");
  }

  @Test
  void aClientInterpretationIsReplaced() {
    String json =
        vital("8310-5", "39", "Cel")
            .replace(
                "\"valueQuantity\"",
                "\"interpretation\": [ { \"coding\": [ { \"system\": \""
                    + ObservationValidator.INTERPRETATION_SYSTEM
                    + "\", \"code\": \"N\" } ] } ], \"valueQuantity\"");
    assertThat(flag(validated(json).getInterpretation())).isEqualTo("H");
  }

  // ---------- units ----------

  @Test
  void aUnitOutsideTheTableIsRejected() {
    assertInvalid(
        vital("8310-5", "98.6", "[degF]"),
        "Observation.valueQuantity.code must be the UCUM unit Cel");
  }

  @Test
  void aNonUcumSystemIsRejected() {
    assertInvalid(
        vital("8310-5", "37", "Cel").replace("http://unitsofmeasure.org", "urn:local:units"),
        "Observation.valueQuantity.code");
  }

  @Test
  void aVitalSignWithoutValueIsRejected() {
    String json = vital("8310-5", "37", "Cel");
    String noValue = json.substring(0, json.indexOf(",\n  \"valueQuantity\"")) + "\n}";
    assertInvalid(noValue, "Observation.valueQuantity is required");
  }

  // ---------- blood pressure: exactly two components ----------

  @Test
  void bloodPressureHasTwoComponents() {
    Observation o = validated(bloodPressure("120", "80"));
    assertThat(flag(o.getInterpretation())).isEqualTo("N");
    assertThat(o.getComponent())
        .allSatisfy(c -> assertThat(flag(c.getInterpretation())).isEqualTo("N"));
  }

  @Test
  void bloodPressureFlagsTheWorstComponent() {
    Observation o = validated(bloodPressure("150", "80"));
    assertThat(flag(o.getInterpretation())).isEqualTo("H");
    assertThat(flag(o.getComponent().get(0).getInterpretation())).isEqualTo("H");
    assertThat(flag(o.getComponent().get(1).getInterpretation())).isEqualTo("N");
    assertThat(flag(validated(bloodPressure("85", "55")).getInterpretation())).isEqualTo("L");
  }

  @Test
  void bloodPressureWithOneComponentIsRejected() {
    String json = bloodPressure("120", "80");
    String one =
        json.substring(
                0,
                json.indexOf(
                    ",\n    { \"code\": { \"coding\": [ { \"system\": \"http://loinc.org\", \"code\": \"8462-4\""))
            + "\n  ]\n}\n";
    assertInvalid(one, "Observation.component must have exactly 2 entries");
  }

  @Test
  void bloodPressureComponentsMustBeSystolicAndDiastolic() {
    assertInvalid(
        bloodPressure("120", "80").replace("8462-4", "8480-6"),
        "Observation.component.code must not repeat");
    assertInvalid(
        bloodPressure("120", "80").replace("8462-4", "8867-4"),
        "Observation.component.code must be systolic or diastolic");
  }

  @Test
  void bloodPressureComponentRangesAreChecked() {
    assertOutOfRange(
        bloodPressure("261", "80"), "Observation.component[systolic].valueQuantity.value");
    assertOutOfRange(
        bloodPressure("120", "29"), "Observation.component[diastolic].valueQuantity.value");
  }

  @Test
  void bloodPressureHasNoValueOfItsOwn() {
    String json =
        bloodPressure("120", "80")
            .replace(
                "\"component\"",
                "\"valueQuantity\": { \"value\": 120, \"system\": \"http://unitsofmeasure.org\","
                    + " \"code\": \"mm[Hg]\" }, \"component\"");
    assertInvalid(json, "Observation.value[x] is not allowed for blood pressure");
  }

  @Test
  void componentsAreOnlyForBloodPressure() {
    String json =
        bloodPressure("120", "80")
            .replace("85354-9", "8867-4")
            .replace(
                "\"component\"",
                "\"valueQuantity\": { \"value\": 70, \"system\": \"http://unitsofmeasure.org\","
                    + " \"code\": \"/min\" }, \"component\"");
    assertInvalid(json, "Observation.component is allowed only for blood pressure");
  }

  // ---------- structure (01 §4.7) ----------

  @Test
  void anUnknownVitalSignCodeIsRejected() {
    assertInvalid(vital("2339-0", "5", "mmol/L"), "Unknown Observation code");
  }

  @Test
  void aVitalSignNeedsAnEncounter() {
    assertInvalid(
        vital("8310-5", "37", "Cel")
            .replace(
                "\"encounter\": { \"reference\": \"Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e10\" },",
                ""),
        "Observation.encounter is required");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "\"status\": \"final\" | \"status\": \"preliminary\" | Observation.status",
        "\"code\": \"vital-signs\" | \"code\": \"exam\" | Observation.category",
        "\"system\": \"http://loinc.org\", \"code\": \"8310-5\" | \"system\": \"http://snomed.info/sct\", \"code\": \"8310-5\" | Observation.code",
        "\"Patient/0192f8a4 | \"Group/0192f8a4 | Observation.subject",
        "\"Encounter/0192f8a4 | \"EpisodeOfCare/0192f8a4 | Observation.encounter",
        "\"Practitioner/0192f8a4 | \"Patient/0192f8a4 | Observation.performer",
        "\"effectiveDateTime\": \"2026-12-03T01:20:00Z\", | | Observation.effectiveDateTime",
      })
  void structureOutsideTheProfileIsRejected(String from, String to, String path) {
    assertInvalid(vital("8310-5", "37", "Cel").replace(from, to == null ? "" : to), path);
  }

  @Test
  void exactlyOnePerformer() {
    assertInvalid(
        vital("8310-5", "37", "Cel")
            .replace(
                "\"performer\": [ {", "\"performer\": [ { \"reference\": \"Practitioner/x1\" }, {"),
        "Observation.performer must have exactly one entry");
  }

  @Test
  void derivedFromIsADocumentReference() {
    String ok =
        vital("8310-5", "37", "Cel")
            .replace(
                "\"performer\"",
                "\"derivedFrom\": [ { \"reference\": \"DocumentReference/d1\" } ], \"performer\"");
    assertThatCode(() -> validated(ok)).doesNotThrowAnyException();
    assertInvalid(ok.replace("DocumentReference/d1", "Media/d1"), "Observation.derivedFrom");
  }

  // ---------- laboratory: free LOINC ----------

  @Test
  void laboratoryNeedsNoEncounterAndAcceptsAnyLoinc() {
    Observation o =
        validated(
            laboratory(
                ", \"valueQuantity\": { \"value\": 120, \"unit\": \"mg/dL\","
                    + " \"system\": \"http://unitsofmeasure.org\", \"code\": \"mg/dL\" },"
                    + " \"referenceRange\": [ { \"low\": { \"value\": 70 }, \"high\": { \"value\": 99 } } ]"));
    assertThat(flag(o.getInterpretation())).isEqualTo("H");
  }

  @Test
  void laboratoryWithoutReferenceRangeOrValueHasNoInterpretation() {
    assertThat(
            validated(
                    laboratory(
                        ", \"valueQuantity\": { \"value\": 5.4, \"system\": \"http://unitsofmeasure.org\","
                            + " \"code\": \"mmol/L\" }"))
                .getInterpretation())
        .isEmpty();
    assertThat(validated(laboratory("")).getInterpretation()).isEmpty();
  }

  @Test
  void laboratoryQuantityMustBeUcum() {
    assertInvalid(
        laboratory(", \"valueQuantity\": { \"value\": 5.4, \"unit\": \"mmol/L\" }"),
        "Observation.valueQuantity must use a UCUM unit");
  }

  @ParameterizedTest
  @CsvSource({"N, 5, 1, 10", "L, 0.5, 1, 10", "H, 11, 1, 10"})
  void compareUsesBothBounds(String flag, String value, String low, String high) {
    assertThat(
            ObservationValidator.compare(
                new java.math.BigDecimal(value),
                new java.math.BigDecimal(low),
                new java.math.BigDecimal(high)))
        .isEqualTo(flag);
  }

  @Test
  void worseRanksHAboveLAboveN() {
    assertThat(ObservationValidator.worse(null, "N")).isEqualTo("N");
    assertThat(ObservationValidator.worse("N", null)).isEqualTo("N");
    assertThat(ObservationValidator.worse("N", "L")).isEqualTo("L");
    assertThat(ObservationValidator.worse("L", "H")).isEqualTo("H");
    assertThat(ObservationValidator.worse("N", "N")).isEqualTo("N");
  }
}
