package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.junit.jupiter.api.Test;
import org.shp.web.errors.ShpException;

/** CLN-19 (US-027; 09 C18; 01 §4.9): recording and updating allergies. Fake data only. */
class AllergyServiceTest {
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  static final String NURSE = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e07";
  static final Instant NOW = Instant.parse("2026-12-03T02:00:00Z");

  static final class MemoryAllergies implements Allergies {
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
  }

  final MemoryAllergies allergies = new MemoryAllergies();
  final AllergyService service = new AllergyService(allergies, Clock.fixed(NOW, ZoneOffset.UTC));

  static String allergy(String extra) {
    return """
    {
      "resourceType": "AllergyIntolerance",
      "clinicalStatus": { "coding": [ { "system": "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical", "code": "active" } ] },
      "code": { "text": "Penicillin (test)" },
      "patient": { "reference": "Patient/%s" },
      "criticality": "high",
      "reaction": [ { "manifestation": [ { "text": "Rash (test)" } ] } ]%s
    }
    """
        .formatted(PAT, extra);
  }

  @Test
  void aNurseRecordsAnAllergyWithTheServersFields() {
    String json =
        allergy(
            ", \"recorder\": { \"reference\": \"Practitioner/other\" }, \"recordedDate\": \"2020-01-01\","
                + " \"verificationStatus\": { \"coding\": [ { \"code\": \"refuted\" } ] }");
    var v = service.create(json, NURSE, "User/nurse-sub");
    AllergyIntolerance a = v.resource();
    assertThat(v.version()).isEqualTo(1);
    assertThat(a.getRecorder().getReference()).isEqualTo("Practitioner/" + NURSE);
    assertThat(a.getRecordedDate().toInstant()).isEqualTo(NOW);
    assertThat(a.hasVerificationStatus()).isFalse(); // outside the profile: dropped
    assertThat(a.getCode().getText()).isEqualTo("Penicillin (test)");
    assertThat(a.getReactionFirstRep().getManifestationFirstRep().getText())
        .isEqualTo("Rash (test)");
    assertThat(allergies.rows.values().iterator().next().search())
        .containsEntry("clinicalStatus", "active")
        .containsEntry("criticality", "high");
  }

  @Test
  void profileRulesAreChecked() {
    assertInvalid(
        allergy("").replace("\"active\"", "\"resolved\""), "AllergyIntolerance.clinicalStatus");
    assertInvalid(
        allergy("").replace("Penicillin (test)", "x".repeat(201)), "AllergyIntolerance.code.text");
    assertInvalid(allergy("").replace("\"Patient/", "\"Group/"), "AllergyIntolerance.patient");
    assertInvalid(
        allergy("")
            .replace(
                "[ { \"manifestation\": [ { \"text\": \"Rash (test)\" } ] } ]",
                "[ { \"description\": \"x\" } ]"),
        "AllergyIntolerance.reaction.manifestation is required");
    assertThat(allergies.rows).isEmpty();
  }

  @Test
  void aCallerWithoutAPractitionerProfileIsRejected() {
    assertThatThrownBy(() -> service.create(allergy(""), null, "User/x"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().status()).isEqualTo(403));
  }

  @Test
  void updateChangesTheClinicalElementsOnly() {
    String id =
        service.create(allergy(""), NURSE, "User/nurse-sub").resource().getIdElement().getIdPart();
    String body =
        allergy("")
            .replace("\"active\"", "\"inactive\"")
            .replace("\"Patient/" + PAT, "\"Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e99")
            .replace(
                "\"resourceType\": \"AllergyIntolerance\",",
                "\"resourceType\": \"AllergyIntolerance\", \"id\": \"" + id + "\",");
    var v = service.update(id, body, 1, "User/doc-sub");
    assertThat(v.version()).isEqualTo(2);
    assertThat(v.resource().getClinicalStatus().getCodingFirstRep().getCode())
        .isEqualTo("inactive");
    assertThat(v.resource().getPatient().getReference()).isEqualTo("Patient/" + PAT); // kept
    assertThat(v.resource().getRecorder().getReference())
        .isEqualTo("Practitioner/" + NURSE); // kept
    assertThat(allergies.rows.get(id).search()).containsEntry("clinicalStatus", "inactive");
  }

  @Test
  void updateChecksIdVersionAndExistence() {
    String id =
        service.create(allergy(""), NURSE, "User/nurse-sub").resource().getIdElement().getIdPart();
    String other =
        allergy("")
            .replace(
                "\"resourceType\": \"AllergyIntolerance\",",
                "\"resourceType\": \"AllergyIntolerance\", \"id\": \"other\",");
    assertThatThrownBy(() -> service.update(id, other, 1, "User/x"))
        .isInstanceOf(ShpException.class);
    assertThatThrownBy(() -> service.update(id, allergy(""), 7, "User/x"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("version-conflict"));
    assertThatThrownBy(() -> service.update("missing", allergy(""), 1, "User/x"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("not-found"));
  }

  @Test
  void listByPatient() {
    service.create(allergy(""), NURSE, "User/nurse-sub");
    assertThat(service.byPatient(PAT, 20)).hasSize(1);
    assertThat(service.byPatient("someone-else", 20)).isEmpty();
  }

  void assertInvalid(String json, String detail) {
    assertThatThrownBy(() -> service.create(json, NURSE, "User/nurse-sub"))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("validation-failed");
              assertThat(e.detail()).startsWith(detail);
            });
  }
}
