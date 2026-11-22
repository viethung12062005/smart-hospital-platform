package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.shp.authz.AuthorizationEngine;
import org.shp.authz.AuthzMatrix;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;

/**
 * CLN-19 done-when "Care doctor and Nurse write; patient reads own": the AllergyIntolerance rules
 * of {@code contracts/authz/matrix.yaml}. Fake data only.
 */
class AllergyAuthzTest {
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  final AuthorizationEngine engine =
      new AuthorizationEngine(
          AuthzMatrix.loadEmbedded(),
          Clock.fixed(Instant.parse("2026-12-03T02:00:00Z"), ZoneOffset.UTC));

  static final Principal DOCTOR = new Principal("d", Set.of("doctor"), "doc-1");
  static final Principal NURSE = new Principal("n", Set.of("nurse"), "nur-1");
  static final Principal PATIENT = new Principal("p", Set.of("patient"), PAT);

  boolean allowed(Principal p, String action, boolean care, boolean breakGlass) {
    var ctx =
        ResourceContext.builder()
            .patientId(PAT)
            .hasCareRelationship(care)
            .hasActiveBreakGlass(breakGlass)
            .build();
    return engine.decide(p, "AllergyIntolerance", action, ctx).allowed();
  }

  @Test
  void aCareDoctorAndANurseWrite() {
    for (String action : new String[] {"create", "update"}) {
      assertThat(allowed(DOCTOR, action, true, false)).isTrue();
      assertThat(allowed(DOCTOR, action, false, false)).isFalse();
      assertThat(allowed(DOCTOR, action, false, true)).isFalse(); // break-glass is read only
      assertThat(allowed(NURSE, action, false, false)).isTrue();
      assertThat(allowed(PATIENT, action, false, false)).isFalse();
    }
  }

  @Test
  void thePatientReadsOwnAndDoctorsReadWithCareOrBreakGlass() {
    assertThat(allowed(PATIENT, "read", false, false)).isTrue();
    assertThat(
            engine
                .decide(
                    new Principal("p2", Set.of("patient"), "someone-else"),
                    "AllergyIntolerance",
                    "read",
                    ResourceContext.builder().patientId(PAT).build())
                .allowed())
        .isFalse();
    assertThat(allowed(DOCTOR, "read", true, false)).isTrue();
    assertThat(allowed(DOCTOR, "read", false, true)).isTrue();
    assertThat(allowed(DOCTOR, "read", false, false)).isFalse();
    assertThat(allowed(NURSE, "read", false, false)).isTrue();
  }
}
