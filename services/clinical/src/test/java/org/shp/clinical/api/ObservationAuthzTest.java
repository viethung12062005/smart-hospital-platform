package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.shp.authz.AuthorizationEngine;
import org.shp.authz.AuthzDecision;
import org.shp.authz.AuthzMatrix;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;

/**
 * CLN-17 (US-025; 15 §3; TC-AC-VT.2): the Observation rules of {@code contracts/authz/matrix.yaml}
 * with the facts the endpoints build. Fake data only.
 */
class ObservationAuthzTest {
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  final AuthorizationEngine engine =
      new AuthorizationEngine(
          AuthzMatrix.loadEmbedded(),
          Clock.fixed(Instant.parse("2026-12-03T02:00:00Z"), ZoneOffset.UTC));

  static final Principal DOCTOR = new Principal("d", Set.of("doctor"), "doc-1");
  static final Principal NURSE = new Principal("n", Set.of("nurse"), "nur-1");
  static final Principal PATIENT = new Principal("p", Set.of("patient"), PAT);

  static ResourceContext ctx(boolean care, boolean finished) {
    return ResourceContext.builder()
        .patientId(PAT)
        .hasCareRelationship(care)
        .encounterFinished(finished)
        .build();
  }

  AuthzDecision decide(Principal p, String action, ResourceContext ctx) {
    return engine.decide(p, "Observation", action, ctx);
  }

  /** TC-AC-VT.2: a doctor with no treatment relationship cannot record vital signs. */
  @Test
  void aDoctorWithoutCareIsDeniedWithNoCareRelationship() {
    AuthzDecision d = decide(DOCTOR, "create", ctx(false, false));
    assertThat(d.allowed()).isFalse();
    assertThat(d.denyReason()).isEqualTo("no-care-relationship");
    assertThat(decide(DOCTOR, "create", ctx(true, false)).allowed()).isTrue();
  }

  @Test
  void aNurseRecordsWithoutCare() {
    assertThat(decide(NURSE, "create", ctx(false, false)).allowed()).isTrue();
  }

  @Test
  void aPatientReadsOwnDataOfFinishedVisitsAndNeverWrites() {
    assertThat(decide(PATIENT, "read", ctx(false, true)).allowed()).isTrue();
    assertThat(decide(PATIENT, "read", ctx(false, false)).allowed()).isFalse();
    assertThat(decide(PATIENT, "create", ctx(false, true)).allowed()).isFalse();
  }
}
