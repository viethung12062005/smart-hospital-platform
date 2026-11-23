package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.shp.clinical.app.Conditions;
import org.shp.clinical.app.Encounters;
import org.shp.clinical.app.Observations;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * CLN-22 (US-030; 15 §6): a patient sees only their own Encounter, Observation and Condition of a
 * FINISHED visit; anything else answers 404 (existence is not revealed); searches are forced to the
 * caller's own record. Fake data only.
 */
class PatientOwnDataTest {
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  static final String OTHER = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e99";
  static final String ENC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e10";
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-03T02:00:00Z"), ZoneOffset.UTC);
  static final Principal PATIENT = new Principal("pat-sub", Set.of("patient"), PAT);

  final Encounters encounters = mock(Encounters.class);
  final Observations observations = mock(Observations.class);
  final Conditions conditions = mock(Conditions.class);
  final ClinicalReads reads = mock(ClinicalReads.class);
  final AuthorizationEngine engine = new AuthorizationEngine(AuthzMatrix.loadEmbedded(), CLOCK);

  void encounter(String patientId, String status) {
    when(encounters.find(ENC))
        .thenReturn(
            Optional.of(
                new Encounters.Stored(
                    ENC,
                    patientId,
                    1,
                    new Document("resourceType", "Encounter"),
                    new Document("status", status).append("practitionerId", "d1"))));
  }

  boolean allowed(String resource, ResourceContext ctx) {
    return engine.decide(PATIENT, resource, "read", ctx).allowed();
  }

  // ---------- reads ----------

  @Test
  void ownFinishedDataIsReadableAndAnythingElseIsDenied() {
    var encounterFacts = new ClinicalFacts().encounterFacts(encounters, reads, CLOCK);
    var observationFacts =
        new ObservationController.Facts().observationFacts(observations, encounters, reads, CLOCK);
    var conditionFacts =
        new ConditionController.Facts().conditionFacts(conditions, encounters, reads, CLOCK);
    when(observations.find("o1"))
        .thenReturn(
            Optional.of(
                new Observations.Stored(
                    "o1", PAT, 1, new Document(), new Document("encounterId", ENC))));
    when(conditions.find("c1"))
        .thenReturn(
            Optional.of(
                new Conditions.Stored(
                    "c1", PAT, 1, new Document(), new Document("encounterId", ENC))));
    var req = new MockHttpServletRequest();

    encounter(PAT, "finished");
    assertThat(allowed("Encounter", encounterFacts.resolve(req, Map.of("id", ENC), PATIENT)))
        .isTrue();
    assertThat(allowed("Observation", observationFacts.resolve(req, Map.of("id", "o1"), PATIENT)))
        .isTrue();
    assertThat(allowed("Condition", conditionFacts.resolve(req, Map.of("id", "c1"), PATIENT)))
        .isTrue();

    encounter(PAT, "in-progress"); // unfinished visit
    assertThat(allowed("Encounter", encounterFacts.resolve(req, Map.of("id", ENC), PATIENT)))
        .isFalse();
    assertThat(allowed("Observation", observationFacts.resolve(req, Map.of("id", "o1"), PATIENT)))
        .isFalse();
    assertThat(allowed("Condition", conditionFacts.resolve(req, Map.of("id", "c1"), PATIENT)))
        .isFalse();

    encounter(OTHER, "finished"); // someone else's visit
    assertThat(allowed("Encounter", encounterFacts.resolve(req, Map.of("id", ENC), PATIENT)))
        .isFalse();
  }

  /** A denied read answers 404, not 403 (07 §4, 15 §6): the read endpoints declare it. */
  @Test
  void deniedReadsAre404() throws NoSuchMethodException {
    for (Method m :
        new Method[] {
          EncounterController.class.getMethod("read", String.class),
          ObservationController.class.getMethod("read", String.class),
          ConditionController.class.getMethod("read", String.class)
        }) {
      RequireShpAuthz a = m.getAnnotation(RequireShpAuthz.class);
      assertThat(a.action()).isEqualTo("read");
      assertThat(a.denyAsNotFound()).as(m.toString()).isTrue();
    }
  }

  // ---------- searches ----------

  @Test
  void aPatientSearchIsForcedToTheirOwnRecord() {
    var req = new MockHttpServletRequest("GET", "/fhir/Observation");
    req.addParameter("patient", OTHER);
    assertThat(ClinicalFacts.searchPatient(req, PATIENT)).isEqualTo(PAT);
    assertThat(ClinicalFacts.searchPatient(new MockHttpServletRequest(), PATIENT)).isEqualTo(PAT);

    var facts = new ClinicalFacts().patientQueryFacts(reads, CLOCK).resolve(req, Map.of(), PATIENT);
    assertThat(facts.patientId()).isEqualTo(PAT);
    assertThat(allowed("Observation", facts)).isTrue();
  }

  @Test
  void aPatientAccountWithoutARecordIs404() {
    var noRecord = new Principal("new-sub", Set.of("patient"), null);
    assertThatThrownBy(() -> ClinicalFacts.searchPatient(new MockHttpServletRequest(), noRecord))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("not-found"));
  }

  @Test
  void staffStillNameThePatient() {
    var nurse = new Principal("n", Set.of("nurse"), "n1");
    var req = new MockHttpServletRequest();
    req.addParameter("patient", "Patient/" + OTHER);
    assertThat(ClinicalFacts.searchPatient(req, nurse)).isEqualTo(OTHER);
    assertThatThrownBy(() -> ClinicalFacts.searchPatient(new MockHttpServletRequest(), nurse))
        .isInstanceOf(ShpException.class);
  }
}
