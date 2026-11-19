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
 * CLN-18 (US-026; AC-04.1; 15 §3): the Condition rules of {@code contracts/authz/matrix.yaml}. Fake
 * data only.
 */
class ConditionAuthzTest {
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  final AuthorizationEngine engine =
      new AuthorizationEngine(
          AuthzMatrix.loadEmbedded(),
          Clock.fixed(Instant.parse("2026-12-03T02:00:00Z"), ZoneOffset.UTC));

  static final Principal DOCTOR = new Principal("d", Set.of("doctor"), "doc-1");
  static final Principal NURSE = new Principal("n", Set.of("nurse"), "nur-1");
  static final Principal PATIENT = new Principal("p", Set.of("patient"), PAT);

  AuthzDecision decide(Principal p, String action, ResourceContext ctx) {
    return engine.decide(p, "Condition", action, ctx);
  }

  static ResourceContext ctx(boolean care, boolean breakGlass, boolean finished) {
    return ResourceContext.builder()
        .patientId(PAT)
        .hasCareRelationship(care)
        .hasActiveBreakGlass(breakGlass)
        .encounterFinished(finished)
        .build();
  }

  @Test
  void aDoctorWithCareRecordsAndCorrects() {
    assertThat(decide(DOCTOR, "create", ctx(true, false, false)).allowed()).isTrue();
    assertThat(decide(DOCTOR, "update", ctx(true, false, false)).allowed()).isTrue();
  }

  /** US-026 AC-2: no care, even during break-glass → 403 no-care-relationship. */
  @Test
  void breakGlassDoesNotAllowRecording() {
    AuthzDecision d = decide(DOCTOR, "create", ctx(false, true, false));
    assertThat(d.allowed()).isFalse();
    assertThat(d.denyReason()).isEqualTo("no-care-relationship");
    assertThat(decide(DOCTOR, "update", ctx(false, true, false)).allowed()).isFalse();
    // but break-glass may read (ETREAT)
    assertThat(decide(DOCTOR, "read", ctx(false, true, false)).allowed()).isTrue();
  }

  @Test
  void nursesReadButNeverWrite() {
    assertThat(decide(NURSE, "read", ctx(false, false, false)).allowed()).isTrue();
    assertThat(decide(NURSE, "create", ctx(false, false, false)).allowed()).isFalse();
    assertThat(decide(NURSE, "update", ctx(false, false, false)).allowed()).isFalse();
  }

  @Test
  void aPatientReadsOwnDiagnosesOfFinishedVisitsOnly() {
    assertThat(decide(PATIENT, "read", ctx(false, false, true)).allowed()).isTrue();
    assertThat(decide(PATIENT, "read", ctx(false, false, false)).allowed()).isFalse();
    assertThat(decide(PATIENT, "create", ctx(false, false, true)).allowed()).isFalse();
    Principal other = new Principal("p2", Set.of("patient"), "someone-else");
    assertThat(decide(other, "read", ctx(false, false, true)).allowed()).isFalse();
  }
}
