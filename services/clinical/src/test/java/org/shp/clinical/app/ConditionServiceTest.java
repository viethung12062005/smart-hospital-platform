package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.hl7.fhir.r4.model.Condition;
import org.junit.jupiter.api.Test;
import org.shp.clinical.infra.TerminologyService;
import org.shp.web.errors.ShpException;

/** CLN-18 (US-026; 09 C17; 01 §4.8): recording and correcting diagnoses. Fake data only. */
public class ConditionServiceTest {
  static final String ENC = EncounterServiceTest.ENC;
  static final String PAT = EncounterServiceTest.PAT;
  static final String DOC = EncounterServiceTest.DOC;
  static final Instant NOW = Instant.parse("2026-12-03T02:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  public static final class MemoryConditions implements Conditions {
    public final Map<String, Stored> rows = new LinkedHashMap<>();

    @Override
    public Stored create(
        String id, String patientId, Document resource, Document search, String by) {
      Stored s = new Stored(id, patientId, 1, resource, search);
      rows.put(id, s);
      return s;
    }

    @Override
    public Optional<Stored> find(String id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Stored update(
        Stored current, Document resource, Document search, int expected, String by) {
      Stored now = rows.get(current.id());
      if (now.version() != expected) {
        throw new ShpException("version-conflict");
      }
      Stored next = new Stored(now.id(), now.patientId(), expected + 1, resource, search);
      rows.put(now.id(), next);
      return next;
    }

    @Override
    public List<Stored> byPatient(String patientId, int limit) {
      return rows.values().stream()
          .filter(s -> s.patientId().equals(patientId))
          .sorted(Comparator.comparing((Stored s) -> s.search().getDate("recordedDate")).reversed())
          .limit(limit)
          .toList();
    }

    @Override
    public List<Stored> byEncounter(String encounterId) {
      return rows.values().stream()
          .filter(s -> encounterId.equals(s.search().getString("encounterId")))
          .toList();
    }
  }

  static TerminologyService icd10() {
    TerminologyService t = mock(TerminologyService.class);
    when(t.isKnown(eq("icd10-subset"), anyString()))
        .thenAnswer(a -> Set.of("I10", "J06.9", "E11.9").contains(a.getArgument(1, String.class)));
    return t;
  }

  final EncounterServiceTest.MemoryEncounters encounters =
      new EncounterServiceTest.MemoryEncounters();
  final MemoryConditions conditions = new MemoryConditions();
  final ConditionService service = new ConditionService(conditions, encounters, icd10(), CLOCK);

  /** An encounter diagnosis; {@code extra} adds elements before the closing brace. */
  static String diagnosis(String code, String extra) {
    return """
    {
      "resourceType": "Condition",
      "clinicalStatus": { "coding": [ { "system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active" } ] },
      "verificationStatus": { "coding": [ { "system": "http://terminology.hl7.org/CodeSystem/condition-ver-status", "code": "confirmed" } ] },
      "category": [ { "coding": [ { "system": "http://terminology.hl7.org/CodeSystem/condition-category", "code": "encounter-diagnosis" } ] } ],
      "code": { "coding": [ { "system": "http://hl7.org/fhir/sid/icd-10", "code": "%s" } ] },
      "subject": { "reference": "Patient/%s" },
      "encounter": { "reference": "Encounter/%s" }%s
    }
    """
        .formatted(code, PAT, ENC, extra);
  }

  ConditionService.View record(String json) {
    return service.create(json, DOC, "User/doc-sub");
  }

  // ---------- AC-1: 201, recorder = the doctor ----------

  @Test
  void aDiagnosisIsRecordedByTheCallingDoctor() {
    encounters.arrived(ENC, PAT, "in-progress", NOW.minusSeconds(900));
    String json =
        diagnosis(
            "I10",
            ", \"recorder\": { \"reference\": \"Practitioner/someone-else\" }, \"recordedDate\": \"2020-01-01\"");
    var v = record(json);
    Condition c = v.resource();
    assertThat(v.version()).isEqualTo(1);
    assertThat(c.getRecorder().getReference()).isEqualTo("Practitioner/" + DOC);
    assertThat(c.getRecordedDate().toInstant()).isEqualTo(NOW);
    assertThat(conditions.rows.values().iterator().next().search())
        .containsEntry("encounterId", ENC)
        .containsEntry("category", "encounter-diagnosis")
        .containsEntry("code", "I10")
        .containsEntry("clinicalStatus", "active")
        .containsEntry("verificationStatus", "confirmed");
  }

  @Test
  void anUnderlyingConditionNeedsNoEncounter() {
    String json =
        diagnosis("E11.9", "")
            .replace("encounter-diagnosis", "problem-list-item")
            .replace(",\n  \"encounter\": { \"reference\": \"Encounter/" + ENC + "\" }", "");
    assertThat(record(json).resource().hasEncounter()).isFalse();
  }

  // ---------- AC-3: 422 invalid-code ----------

  @Test
  void aCodeOutsideTheIcd10TableIs422() {
    encounters.arrived(ENC, PAT, "in-progress", NOW);
    assertThatThrownBy(() -> record(diagnosis("Z99.99", "")))
        .isInstanceOf(ConditionService.InvalidCodeException.class)
        .hasMessage("Condition.code is not in the ICD-10 table.");
    assertThat(ConditionService.INVALID_CODE.status()).isEqualTo(422);
    assertThat(conditions.rows).isEmpty();
  }

  // ---------- structure ----------

  @Test
  void structureRulesAreChecked() {
    encounters.arrived(ENC, PAT, "in-progress", NOW);
    assertInvalid(
        diagnosis("I10", "").replace("\"active\"", "\"remission\""), "Condition.clinicalStatus");
    assertInvalid(
        diagnosis("I10", "").replace("\"confirmed\"", "\"entered-in-error\""),
        "Condition.verificationStatus");
    assertInvalid(
        diagnosis("I10", "").replace("encounter-diagnosis", "health-concern"),
        "Condition.category");
    assertInvalid(
        diagnosis("I10", "").replace("http://hl7.org/fhir/sid/icd-10", "http://snomed.info/sct"),
        "Condition.code must be an ICD-10 code");
    assertInvalid(diagnosis("I10", "").replace("\"Patient/", "\"Group/"), "Condition.subject");
    assertInvalid(
        diagnosis("I10", "")
            .replace(",\n  \"encounter\": { \"reference\": \"Encounter/" + ENC + "\" }", ""),
        "Condition.encounter is required");
    assertInvalid(
        diagnosis("I10", ", \"note\": [ { \"text\": \"" + "x".repeat(2_001) + "\" } ]"),
        "Condition.note.text");
    assertThat(conditions.rows).isEmpty();
  }

  @Test
  void theEncounterMustExistBelongToThePatientAndBeOpen() {
    assertInvalid(diagnosis("I10", ""), "Condition.encounter does not exist");
    encounters.arrived(ENC, "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e99", "in-progress", NOW);
    assertInvalid(diagnosis("I10", ""), "Condition.encounter belongs to another patient");
    encounters.arrived(ENC, PAT, "finished", NOW);
    assertThatThrownBy(() -> record(diagnosis("I10", "")))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> assertThat(e.info().code()).isEqualTo("invalid-state-transition"));
  }

  @Test
  void aCallerWithoutAPractitionerProfileIsRejected() {
    assertThatThrownBy(() -> service.create(diagnosis("I10", ""), null, "User/x"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().status()).isEqualTo(403));
  }

  // ---------- AC-4: entered-in-error keeps the record ----------

  @Test
  void enteredInErrorKeepsTheRecordAndIsFinal() {
    encounters.arrived(ENC, PAT, "in-progress", NOW);
    String id = record(diagnosis("I10", "")).resource().getIdElement().getIdPart();
    String correction =
        diagnosis("J06.9", "")
            .replace("\"confirmed\"", "\"entered-in-error\"")
            .replace(
                "\"resourceType\": \"Condition\",",
                "\"resourceType\": \"Condition\", \"id\": \"" + id + "\",");
    var v = service.update(id, correction, 1, "User/doc-sub");
    assertThat(v.version()).isEqualTo(2);
    assertThat(v.resource().getVerificationStatus().getCodingFirstRep().getCode())
        .isEqualTo("entered-in-error");
    // the code is not editable: the stored diagnosis stays I10
    assertThat(v.resource().getCode().getCodingFirstRep().getCode()).isEqualTo("I10");
    assertThat(conditions.rows).hasSize(1);
    assertThat(conditions.rows.get(id).search())
        .containsEntry("verificationStatus", "entered-in-error");

    assertThatThrownBy(() -> service.update(id, diagnosis("I10", ""), 2, "User/doc-sub"))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> assertThat(e.info().code()).isEqualTo("invalid-state-transition"));
  }

  @Test
  void updateChecksIdAndVersion() {
    encounters.arrived(ENC, PAT, "in-progress", NOW);
    String id = record(diagnosis("I10", "")).resource().getIdElement().getIdPart();
    String other =
        diagnosis("I10", "")
            .replace(
                "\"resourceType\": \"Condition\",",
                "\"resourceType\": \"Condition\", \"id\": \"other\",");
    assertInvalid(() -> service.update(id, other, 1, "User/doc-sub"), "Condition.id must match");
    assertThatThrownBy(() -> service.update(id, diagnosis("I10", ""), 5, "User/doc-sub"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("version-conflict"));
    assertThatThrownBy(() -> service.update("missing", diagnosis("I10", ""), 1, "User/doc-sub"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("not-found"));
  }

  // ---------- read and search ----------

  @Test
  void aPatientSeesOnlyDiagnosesOfFinishedVisits() {
    encounters.arrived(ENC, PAT, "in-progress", NOW);
    String id = record(diagnosis("I10", "")).resource().getIdElement().getIdPart();
    assertThat(service.byPatient(PAT, true, 20)).isEmpty();
    assertThat(service.byPatient(PAT, false, 20)).hasSize(1);
    encounters.rows.get(ENC).search().put("status", "finished");
    assertThat(service.byPatient(PAT, true, 20))
        .extracting(c -> c.getIdElement().getIdPart())
        .containsExactly(id);
    assertThat(service.read(id).version()).isEqualTo(1);
  }

  void assertInvalid(String json, String detail) {
    assertInvalid(() -> record(json), detail);
  }

  static void assertInvalid(Runnable call, String detail) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("validation-failed");
              assertThat(e.detail()).startsWith(detail);
            });
  }
}
