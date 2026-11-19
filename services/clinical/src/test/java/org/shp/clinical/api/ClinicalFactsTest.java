package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.authz.AuthorizationEngine;
import org.shp.authz.AuthzMatrix;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.clinical.app.ClinicalReads;
import org.shp.clinical.app.ClinicalReads.CareRow;
import org.shp.clinical.app.Encounters;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * CLN-15 (15 §3, §4): the facts the matrix decides on for Encounters, checked against the real
 * {@code contracts/authz/matrix.yaml} rules. Fake data only.
 */
class ClinicalFactsTest {
  static final String ENC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e10";
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  static final String DOC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03";
  static final Instant NOW = Instant.parse("2026-12-03T02:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  static final Principal DOCTOR = new Principal("doc-sub", Set.of("doctor"), DOC);
  static final Principal OTHER_DOCTOR =
      new Principal("doc2-sub", Set.of("doctor"), "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e04");
  static final Principal NURSE = new Principal("nurse-sub", Set.of("nurse"), "n1");
  static final Principal PATIENT = new Principal("pat-sub", Set.of("patient"), PAT);
  static final Principal OTHER_PATIENT = new Principal("pat2-sub", Set.of("patient"), "p2");

  final Encounters encounters = mock(Encounters.class);
  final ClinicalReads reads = mock(ClinicalReads.class);
  final ClinicalFacts config = new ClinicalFacts();
  final ResourceContextResolver encounterFacts = config.encounterFacts(encounters, reads, CLOCK);
  final ResourceContextResolver editFacts = config.encounterEditFacts(encounters, reads, CLOCK);
  final ResourceContextResolver queryFacts = config.patientQueryFacts(reads, CLOCK);
  final AuthorizationEngine engine = new AuthorizationEngine(AuthzMatrix.loadEmbedded(), CLOCK);

  void stored(String status) {
    when(encounters.find(ENC))
        .thenReturn(
            Optional.of(
                new Encounters.Stored(
                    ENC,
                    PAT,
                    1,
                    new Document("resourceType", "Encounter"),
                    new Document("status", status).append("practitionerId", DOC))));
  }

  void care(String practitionerId) {
    when(reads.careRelationship(practitionerId, PAT, NOW))
        .thenReturn(Optional.of(new CareRow(NOW.plusSeconds(3600), "appointment")));
  }

  ResourceContext facts(ResourceContextResolver r, Principal p) {
    return r.resolve(new MockHttpServletRequest(), Map.of("id", ENC), p);
  }

  boolean allowed(Principal p, String action, ResourceContext ctx) {
    return engine.decide(p, "Encounter", action, ctx).allowed();
  }

  @Test
  void factsNameThePatientTheAttenderAndTheState() {
    stored("finished");
    care(DOC);
    ResourceContext ctx = facts(encounterFacts, DOCTOR);
    assertThat(ctx.patientId()).isEqualTo(PAT);
    assertThat(ctx.practitionerId()).isEqualTo(DOC);
    assertThat(ctx.hasCareRelationship()).isTrue();
    assertThat(ctx.encounterFinished()).isTrue();
  }

  @Test
  void aDoctorWithCareMayReadAndOneWithoutMayNot() {
    stored("in-progress");
    care(DOC);
    when(reads.careRelationship(anyString(), any(), any())).thenReturn(Optional.empty());
    care(DOC);
    assertThat(allowed(DOCTOR, "read", facts(encounterFacts, DOCTOR))).isTrue();
    assertThat(allowed(OTHER_DOCTOR, "read", facts(encounterFacts, OTHER_DOCTOR))).isFalse();
  }

  @Test
  void breakGlassLetsADoctorRead() {
    stored("in-progress");
    when(reads.careRelationship(anyString(), any(), any())).thenReturn(Optional.empty());
    when(reads.breakGlass(OTHER_DOCTOR.fhirUserId(), PAT, NOW))
        .thenReturn(Optional.of(NOW.plusSeconds(600)));
    ResourceContext ctx = facts(encounterFacts, OTHER_DOCTOR);
    assertThat(ctx.hasActiveBreakGlass()).isTrue();
    assertThat(allowed(OTHER_DOCTOR, "read", ctx)).isTrue();
    assertThat(allowed(OTHER_DOCTOR, "update", ctx)).isFalse(); // break-glass is read only
  }

  @Test
  void aPatientReadsOnlyTheirOwnFinishedVisit() {
    stored("in-progress");
    assertThat(allowed(PATIENT, "read", facts(encounterFacts, PATIENT))).isFalse();
    stored("finished");
    assertThat(allowed(PATIENT, "read", facts(encounterFacts, PATIENT))).isTrue();
    assertThat(allowed(OTHER_PATIENT, "read", facts(encounterFacts, OTHER_PATIENT))).isFalse();
    verify(reads, never()).careRelationship(any(), any(), any()); // no care lookup for patients
  }

  @Test
  void editFactsLetTheServiceAnswer409ForAFinishedVisit() {
    stored("finished");
    care(DOC);
    assertThat(allowed(DOCTOR, "update", facts(encounterFacts, DOCTOR))).isFalse();
    ResourceContext edit = facts(editFacts, DOCTOR);
    assertThat(edit.encounterFinished()).isFalse();
    assertThat(allowed(DOCTOR, "update", edit)).isTrue();
    // care is still required
    when(reads.careRelationship(anyString(), any(), any())).thenReturn(Optional.empty());
    assertThat(allowed(OTHER_DOCTOR, "update", facts(editFacts, OTHER_DOCTOR))).isFalse();
  }

  @Test
  void anUnknownOrMalformedIdIs404() {
    when(encounters.find(ENC)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> facts(encounterFacts, DOCTOR))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("not-found"));
    assertThatThrownBy(
            () -> encounterFacts.resolve(new MockHttpServletRequest(), Map.of("id", "a b"), DOCTOR))
        .isInstanceOf(ShpException.class);
  }

  // ---------- search: patient parameter ----------

  MockHttpServletRequest search(String... patient) {
    var req = new MockHttpServletRequest("GET", "/fhir/Encounter");
    for (String p : patient) {
      req.addParameter("patient", p);
    }
    return req;
  }

  @Test
  void searchFactsComeFromThePatientParameter() {
    care(DOC);
    ResourceContext ctx = queryFacts.resolve(search("Patient/" + PAT), Map.of(), DOCTOR);
    assertThat(ctx.patientId()).isEqualTo(PAT);
    assertThat(allowed(DOCTOR, "read", ctx)).isTrue();
    assertThat(allowed(PATIENT, "read", queryFacts.resolve(search(PAT), Map.of(), PATIENT)))
        .isTrue();
    // CLN-22 (15 §6): another patient's id is replaced by the caller's own, not refused
    ResourceContext forced = queryFacts.resolve(search(PAT), Map.of(), OTHER_PATIENT);
    assertThat(forced.patientId()).isEqualTo(OTHER_PATIENT.fhirUserId());
    assertThat(allowed(OTHER_PATIENT, "read", forced)).isTrue();
    assertThat(allowed(NURSE, "read", queryFacts.resolve(search(PAT), Map.of(), NURSE))).isTrue();
  }

  @Test
  void thePatientParameterIsRequiredOnce() {
    assertThatThrownBy(() -> ClinicalFacts.patientParam(search())).isInstanceOf(ShpException.class);
    assertThatThrownBy(() -> ClinicalFacts.patientParam(search(PAT, PAT)))
        .isInstanceOf(ShpException.class);
    assertThatThrownBy(() -> ClinicalFacts.patientParam(search("Patient/a b")))
        .isInstanceOf(ShpException.class);
  }

  @Test
  void countIsBoundedAndUnknownParametersAreRejected() {
    assertThat(EncounterController.count(Map.of("patient", List.of(PAT)))).isEqualTo(20);
    assertThat(EncounterController.count(Map.of("_count", List.of("100")))).isEqualTo(100);
    for (String bad : List.of("0", "101", "x")) {
      assertThatThrownBy(() -> EncounterController.count(Map.of("_count", List.of(bad))))
          .isInstanceOfSatisfying(
              ShpException.class, e -> assertThat(e.info().code()).isEqualTo("validation-failed"));
    }
    assertThatThrownBy(() -> EncounterController.count(Map.of("status", List.of("finished"))))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> assertThat(e.info().code()).isEqualTo("unsupported-parameter"));
  }
}
