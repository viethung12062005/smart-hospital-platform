package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.shp.authz.AuthorizationEngine;
import org.shp.authz.AuthzDecision;
import org.shp.authz.AuthzMatrix;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.clinical.app.ClinicalReads.CareRow;
import org.shp.clinical.app.ClinicalReads.PatientFacts;
import org.shp.web.errors.ShpException;
import org.springframework.data.mongodb.core.MongoTemplate;

/** CLN-24 (US-034; 09 C20; 15 §5; AC-10.1): break-glass sessions. Fake data only. */
class BreakGlassServiceTest {
  static final String DOC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03";
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  static final Instant NOW = Instant.parse("2026-12-03T02:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final String REASON = "Patient unconscious in the department, no family present (test)";

  final MongoTemplate mongo = mock(MongoTemplate.class);
  final ClinicalReads reads = mock(ClinicalReads.class);
  final BreakGlassService service = new BreakGlassService(mongo, reads, CLOCK);

  void patientExists() {
    when(reads.patient(PAT)).thenReturn(Optional.of(new PatientFacts(null, null, null, null)));
  }

  @Test
  void aSixtyMinuteSessionIsStored() {
    patientExists();
    assertThat(service.start(DOC, PAT, "  " + REASON + "  ")).isEqualTo(NOW.plusSeconds(3600));
    ArgumentCaptor<Document> doc = ArgumentCaptor.forClass(Document.class);
    verify(mongo).insert(doc.capture(), eq("break_glass_sessions"));
    assertThat(doc.getValue())
        .containsEntry("practitionerId", DOC)
        .containsEntry("patientId", PAT)
        .containsEntry("reason", REASON) // trimmed
        .containsEntry("startedAt", Date.from(NOW))
        .containsEntry("expiresAt", Date.from(NOW.plusSeconds(3600)));
    assertThat(doc.getValue().getString("_id")).hasSize(36);
  }

  @Test
  void theReasonIs20To500Characters() {
    patientExists();
    for (String bad :
        new String[] {null, "", "too short reason", "x".repeat(501), " ".repeat(30)}) {
      assertThatThrownBy(() -> service.start(DOC, PAT, bad))
          .isInstanceOfSatisfying(
              ShpException.class, e -> assertThat(e.info().code()).isEqualTo("validation-failed"));
    }
    assertThat(service.start(DOC, PAT, "x".repeat(20))).isNotNull();
    verify(mongo).insert(any(Document.class), anyString());
  }

  @Test
  void careOrALiveSessionIs409() {
    patientExists();
    when(reads.careRelationship(DOC, PAT, NOW))
        .thenReturn(Optional.of(new CareRow(NOW.plusSeconds(60), "appointment")));
    assertConflict();
    when(reads.careRelationship(DOC, PAT, NOW)).thenReturn(Optional.empty());
    when(reads.breakGlass(DOC, PAT, NOW)).thenReturn(Optional.of(NOW.plusSeconds(600)));
    assertConflict();
    verify(mongo, never()).insert(any(Document.class), anyString());
  }

  void assertConflict() {
    assertThatThrownBy(() -> service.start(DOC, PAT, REASON))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("invalid-state-transition");
              assertThat(e.info().status()).isEqualTo(409);
            });
  }

  @Test
  void anUnknownPatientOrANonDoctorIsRejected() {
    when(reads.patient(PAT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.start(DOC, PAT, REASON))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("not-found"));
    assertThatThrownBy(() -> service.start(null, PAT, REASON))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().status()).isEqualTo(403));
    assertThatThrownBy(() -> service.start(DOC, "a b", REASON)).isInstanceOf(ShpException.class);
  }

  // ---------- 15 §5 with the real matrix ----------

  final AuthorizationEngine engine = new AuthorizationEngine(AuthzMatrix.loadEmbedded(), CLOCK);
  static final Principal DOCTOR = new Principal("d", Set.of("doctor"), DOC);

  AuthzDecision decide(String resource, String action, boolean care, boolean breakGlass) {
    return engine.decide(
        DOCTOR,
        resource,
        action,
        ResourceContext.builder()
            .patientId(PAT)
            .hasCareRelationship(care)
            .hasActiveBreakGlass(breakGlass)
            .encounterFinished(false)
            .build());
  }

  @Test
  void startingIsForDoctorsAndIsAuditedAsEtreat() {
    AuthzDecision d = decide("BreakGlass", "start", false, false);
    assertThat(d.allowed()).isTrue();
    assertThat(d.purpose()).isEqualTo("ETREAT");
    assertThat(
            engine
                .decide(
                    new Principal("n", Set.of("nurse"), "n1"),
                    "BreakGlass",
                    "start",
                    ResourceContext.builder().patientId(PAT).build())
                .allowed())
        .isFalse();
  }

  /** Done-when: writing a diagnosis (or vital signs) during the session is 403; reading is not. */
  @Test
  void aSessionAllowsReadingButNotWriting() {
    assertThat(decide("Condition", "create", false, true).allowed()).isFalse();
    assertThat(decide("Observation", "create", false, true).allowed()).isFalse();
    assertThat(decide("Encounter", "update", false, true).allowed()).isFalse();
    assertThat(decide("Encounter", "read", false, true).allowed()).isTrue();
    assertThat(decide("Patient", "read", false, true).allowed()).isTrue();
    assertThat(decide("Encounter", "read", false, true).purpose()).isEqualTo("ETREAT");
  }
}
