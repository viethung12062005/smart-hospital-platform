package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.Test;
import org.shp.web.errors.ShpException;

/** CLN-17 (US-025; 09 C16): recording and reading Observations. Fake data only. */
class ObservationServiceTest {
  // ids used by ObservationValidatorTest.vital(...)
  static final String ENC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e10";
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  static final String NURSE = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e07";
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-03T01:30:00Z"), ZoneOffset.UTC);

  static final class MemoryObservations implements Observations {
    final Map<String, Stored> rows = new LinkedHashMap<>();

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
    public List<Stored> byPatient(
        String patientId,
        Collection<String> encounterIds,
        String category,
        String code,
        int limit) {
      return rows.values().stream()
          .filter(s -> s.patientId().equals(patientId))
          .filter(s ->
              encounterIds == null || encounterIds.contains(s.search().getString("encounterId")))
          .filter(s -> category == null || category.equals(s.search().getString("category")))
          .filter(s -> code == null || code.equals(s.search().getString("code")))
          .sorted(Comparator.comparing((Stored s) -> s.search().getDate("effective")).reversed())
          .limit(limit)
          .toList();
    }
  }

  final EncounterServiceTest.MemoryEncounters encounters =
      new EncounterServiceTest.MemoryEncounters();
  final MemoryObservations observations = new MemoryObservations();
  final ObservationService service =
      new ObservationService(
          observations,
          encounters,
          new ObservationValidator(
              new ObservationCodeValidator(ObservationValidatorTest.vitalSigns())),
          CLOCK);

  ObservationService.View recordAsNurse(String json) {
    return service.create(json, NURSE, false, "User/nurse-sub");
  }

  // ---------- US-025 AC-1: 201 with a server-computed interpretation ----------

  @Test
  void aVitalSignIsStoredWithTheServersFields() {
    encounters.arrived(ENC, PAT, "in-progress", Instant.parse("2026-12-03T01:15:00Z"));
    var v = recordAsNurse(ObservationValidatorTest.vital("8310-5", "38.5", "Cel"));
    Observation o = v.resource();
    assertThat(v.version()).isEqualTo(1);
    assertThat(o.getIdElement().getIdPart()).hasSize(36);
    assertThat(o.getPerformerFirstRep().getReference()).isEqualTo("Practitioner/" + NURSE);
    assertThat(o.getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("H");

    Document search = observations.rows.values().iterator().next().search();
    assertThat(search)
        .containsEntry("encounterId", ENC)
        .containsEntry("category", "vital-signs")
        .containsEntry("code", "8310-5")
        .containsEntry("interpretation", "H");
    assertThat(search.getDate("effective").toInstant())
        .isEqualTo(Instant.parse("2026-12-03T01:20:00Z"));
  }

  @Test
  void clientIdMetaAndPerformerAreReplaced() {
    encounters.arrived(ENC, PAT, "in-progress", Instant.parse("2026-12-03T01:15:00Z"));
    String json =
        ObservationValidatorTest.vital("8867-4", "72", "/min")
            .replace(
                "\"status\": \"final\",",
                "\"id\": \"client-id\", \"meta\": { \"versionId\": \"9\" }, \"status\": \"final\",");
    Observation o = recordAsNurse(json).resource();
    assertThat(o.getIdElement().getIdPart()).isNotEqualTo("client-id");
    assertThat(o.getMeta().getVersionId()).isEqualTo("1");
    // ObservationValidatorTest.vital names another practitioner as performer
    assertThat(o.getPerformerFirstRep().getReference()).isEqualTo("Practitioner/" + NURSE);
  }

  // ---------- AC-2 and AC-3 ----------

  @Test
  void temperature50IsRejectedAndNothingIsSaved() {
    encounters.arrived(ENC, PAT, "in-progress", Instant.parse("2026-12-03T01:15:00Z"));
    assertThatThrownBy(() -> recordAsNurse(ObservationValidatorTest.vital("8310-5", "50", "Cel")))
        .isInstanceOf(ValueOutOfRangeException.class);
    assertThat(observations.rows).isEmpty();
  }

  @Test
  void aVitalSignWithoutEncounterIs400() {
    String json =
        ObservationValidatorTest.vital("8310-5", "37", "Cel")
            .replace(
                "\"encounter\": { \"reference\": \"Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e10\" },",
                "");
    assertRejected(
        () -> recordAsNurse(json), "validation-failed", "Observation.encounter is required");
  }

  @Test
  void theEncounterMustExistBelongToThePatientAndBeOpen() {
    String json = ObservationValidatorTest.vital("8310-5", "37", "Cel");
    assertRejected(
        () -> recordAsNurse(json), "validation-failed", "Observation.encounter does not exist");

    encounters.arrived(ENC, "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e99", "in-progress", Instant.now());
    assertRejected(
        () -> recordAsNurse(json),
        "validation-failed",
        "Observation.encounter belongs to another patient");

    encounters.arrived(ENC, PAT, "finished", Instant.now());
    assertRejected(
        () -> recordAsNurse(json), "invalid-state-transition", "The Encounter is finished");
    assertThat(observations.rows).isEmpty();
  }

  // ---------- laboratory: doctors only (09 C16) ----------

  @Test
  void aNurseCannotRecordALaboratoryResultButADoctorCan() {
    String lab =
        ObservationValidatorTest.laboratory(
            ", \"valueQuantity\": { \"value\": 5.4, \"system\": \"http://unitsofmeasure.org\", \"code\": \"mmol/L\" }");
    assertRejected(() -> recordAsNurse(lab), "forbidden", "Only a doctor");
    var v = service.create(lab, EncounterServiceTest.DOC, true, "User/doc-sub");
    assertThat(v.resource().getEncounter().isEmpty()).isTrue();
    assertThat(observations.rows.values().iterator().next().search().get("encounterId")).isNull();
  }

  @Test
  void aCallerWithoutAPractitionerProfileIsRejected() {
    assertRejected(
        () ->
            service.create(
                ObservationValidatorTest.vital("8310-5", "37", "Cel"), null, false, "User/x"),
        "forbidden",
        "Only a practitioner");
  }

  // ---------- read and search ----------

  @Test
  void readReturnsTheStoredObservation() {
    encounters.arrived(ENC, PAT, "in-progress", Instant.parse("2026-12-03T01:15:00Z"));
    String id =
        recordAsNurse(ObservationValidatorTest.vital("8310-5", "37", "Cel"))
            .resource()
            .getIdElement()
            .getIdPart();
    assertThat(service.read(id).resource().getIdElement().getIdPart()).isEqualTo(id);
    assertThatThrownBy(() -> service.read("unknown")).isInstanceOf(ShpException.class);
  }

  @Test
  void aPatientSeesOnlyObservationsOfFinishedEncounters() {
    encounters.arrived(ENC, PAT, "in-progress", Instant.parse("2026-12-03T01:15:00Z"));
    recordAsNurse(ObservationValidatorTest.vital("8310-5", "37", "Cel"));
    assertThat(service.byPatient(PAT, true, null, null, 20)).isEmpty();
    assertThat(service.byPatient(PAT, false, null, null, 20)).hasSize(1);

    // the visit ends: now the patient sees it
    var stored = encounters.rows.get(ENC);
    stored.search().put("status", "finished");
    assertThat(service.byPatient(PAT, true, null, null, 20)).hasSize(1);
    assertThat(service.byPatient(PAT, true, "laboratory", null, 20)).isEmpty();
    assertThat(service.byPatient(PAT, true, null, "8310-5", 20)).hasSize(1);
  }

  static void assertRejected(Runnable call, String code, String detail) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo(code);
              assertThat(e.detail()).startsWith(detail);
            });
  }
}
