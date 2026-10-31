package org.shp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test sinh từ chính matrix.yaml: sửa ma trận thì test tự đổi theo; thêm rule mà engine hành xử sai
 * là đỏ.
 */
class MatrixDrivenTest {
  static final AuthzMatrix MATRIX = AuthzMatrix.loadEmbedded();
  static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  static final AuthorizationEngine ENGINE =
      new AuthorizationEngine(MATRIX, Clock.fixed(NOW, ZoneOffset.UTC));
  static final List<String> ROLES = List.of("patient", "doctor", "nurse", "admin", "dpo");
  static final List<String> ALL_PREDICATES =
      List.of(
          Predicates.OWN,
          Predicates.LINKED,
          Predicates.LINKED_RECORDS,
          Predicates.GUARDIAN,
          Predicates.SELF_PRACTITIONER,
          Predicates.CARE,
          Predicates.BREAK_GLASS,
          Predicates.RELEASED,
          Predicates.ENCOUNTER_FINISHED,
          Predicates.UNFINISHED_ENCOUNTER,
          Predicates.BEFORE_2H,
          Predicates.NOT_CARE);

  static Stream<String> ruleIds() {
    return MATRIX.rules().stream().map(AuthzRule::id);
  }

  static Stream<Arguments> uncoveredTriples() {
    Set<String> covered = new HashSet<>();
    MATRIX.rules().forEach(r -> covered.add(r.resource() + "|" + r.action() + "|" + r.role()));
    List<Arguments> out = new ArrayList<>();
    MATRIX.rules().stream()
        .map(r -> r.resource() + "|" + r.action())
        .distinct()
        .forEach(
            ra -> {
              String[] p = ra.split("\\|");
              for (String role : ROLES) {
                if (!covered.contains(ra + "|" + role)) {
                  out.add(Arguments.of(p[0], p[1], role));
                }
              }
            });
    return out.stream();
  }

  static AuthzRule rule(String id) {
    return MATRIX.rules().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
  }

  @ParameterizedTest
  @MethodSource("ruleIds")
  void ruleAllowsWhenAllPredicatesHold(String id) {
    AuthzRule r = rule(id);
    var d =
        ENGINE.decide(
            principal(r.role()), r.resource(), r.action(), ctx(Set.copyOf(r.when()), false));
    assertThat(d.allowed()).as("%s phải cho phép: %s", id, d.denyReason()).isTrue();
  }

  @ParameterizedTest
  @MethodSource("ruleIds")
  void ruleDeniesWhenAnySinglePredicateFails(String id) {
    AuthzRule r = rule(id);
    for (String failing : r.when()) {
      Set<String> holding = new HashSet<>(r.when());
      holding.remove(failing);
      var d =
          ENGINE.decide(
              principal(r.role()),
              r.resource(),
              r.action(),
              ctx(holding, failing.equals(Predicates.NOT_CARE)));
      assertThat(d.allowed()).as("%s không được cho phép khi thiếu %s", id, failing).isFalse();
    }
  }

  @ParameterizedTest
  @MethodSource("uncoveredTriples")
  void defaultDenyForRolesWithoutRuleEvenWhenEveryPredicateHolds(
      String resource, String action, String role) {
    assertThat(
            ENGINE
                .decide(principal(role), resource, action, ctx(Set.copyOf(ALL_PREDICATES), false))
                .allowed())
        .isFalse();
  }

  @Test
  void adminAndDpoNeverReachClinicalResources() {
    MATRIX
        .clinicalByResource()
        .forEach(
            (resource, clinical) -> {
              if (!clinical) {
                return;
              }
              MATRIX.rules().stream()
                  .filter(r -> r.resource().equals(resource))
                  .map(AuthzRule::action)
                  .distinct()
                  .forEach(
                      action -> {
                        for (String role : List.of("admin", "dpo")) {
                          assertThat(
                                  ENGINE
                                      .decide(
                                          principal(role),
                                          resource,
                                          action,
                                          ctx(Set.copyOf(ALL_PREDICATES), false))
                                      .allowed())
                              .as("%s chạm %s.%s", role, resource, action)
                              .isFalse();
                        }
                      });
            });
  }

  @Test
  void auditUpdateAndDeleteAreDeniedForEveryone() {
    for (String role : ROLES) {
      var c = ctx(Set.copyOf(ALL_PREDICATES), false);
      assertThat(ENGINE.decide(principal(role), "Audit", "update", c).allowed()).isFalse();
      assertThat(ENGINE.decide(principal(role), "Audit", "delete", c).allowed()).isFalse();
    }
  }

  @Test
  void unknownResourceOrActionIsDenied() {
    var c = ctx(Set.copyOf(ALL_PREDICATES), false);
    assertThat(ENGINE.decide(principal("nurse"), "Nope", "read", c).denyReason())
        .isEqualTo("no-rule");
    assertThat(ENGINE.decide(principal("nurse"), "Patient", "teleport", c).denyReason())
        .isEqualTo("no-rule");
  }

  @Test
  void doctorWithoutCareGetsNoCareRelationshipReason() {
    var d = ENGINE.decide(principal("doctor"), "Patient", "read", ctx(Set.of(), false));
    assertThat(d.allowed()).isFalse();
    assertThat(d.denyReason()).isEqualTo("no-care-relationship");
  }

  @Test
  void roleWithoutAnyRuleGetsRoleNotAllowed() {
    assertThat(
            ENGINE.decide(principal("admin"), "Patient", "read", ctx(Set.of(), false)).denyReason())
        .isEqualTo("role-not-allowed");
  }

  @Test
  void careTakesPrecedenceOverBreakglassForPurpose() {
    var d =
        ENGINE.decide(
            principal("doctor"),
            "Patient",
            "read",
            ctx(Set.of(Predicates.CARE, Predicates.BREAK_GLASS), false));
    assertThat(d.allowed()).isTrue();
    assertThat(d.purpose()).isEqualTo("TREAT");
  }

  @Test
  void breakglassAloneAllowsWithEmergencyPurpose() {
    var d =
        ENGINE.decide(
            principal("doctor"), "Patient", "read", ctx(Set.of(Predicates.BREAK_GLASS), false));
    assertThat(d.allowed()).isTrue();
    assertThat(d.purpose()).isEqualTo("ETREAT");
  }

  @Test
  void decisionCarriesFieldAndHideConstraints() {
    assertThat(
            ENGINE
                .decide(
                    principal("patient"), "Patient", "update", ctx(Set.of(Predicates.OWN), false))
                .fields())
        .contains("telecom");
    assertThat(ENGINE.decide(principal("admin"), "Audit", "read", ctx(Set.of(), false)).hide())
        .contains("reason");
  }

  @Test
  void cancelWindowIsExactlyTwoHours() {
    var p = new Principal("s", Set.of("patient"), "p1");
    assertThat(cancel(p, NOW.plusSeconds(7200))).isTrue();
    assertThat(cancel(p, NOW.plusSeconds(7199))).isFalse();
  }

  private static boolean cancel(Principal p, Instant start) {
    return ENGINE
        .decide(
            p,
            "Appointment",
            "cancel",
            ResourceContext.builder().patientId("p1").appointmentStart(start).build())
        .allowed();
  }

  @Test
  void bookingScopeLinkAllowsAppointmentsButNotClinicalRecords() {
    var p = new Principal("s", Set.of("patient"), "me");
    var booking =
        ResourceContext.builder()
            .patientId("kid")
            .linkedProfile(true)
            .encounterFinished(true)
            .build();
    assertThat(ENGINE.decide(p, "Appointment", "book", booking).allowed()).isTrue();
    assertThat(ENGINE.decide(p, "Encounter", "read", booking).allowed()).isFalse();
    var records =
        ResourceContext.builder()
            .patientId("kid")
            .linkedProfile(true)
            .linkedRecords(true)
            .encounterFinished(true)
            .build();
    assertThat(ENGINE.decide(p, "Encounter", "read", records).allowed()).isTrue();
  }

  @Test
  void onlyGuardianMayUpdateAdministrativeFieldsOfAnotherProfile() {
    var p = new Principal("s", Set.of("patient"), "me");
    var proxy = ResourceContext.builder().patientId("adult").linkedProfile(true).build();
    var guardian =
        ResourceContext.builder().patientId("kid").linkedProfile(true).guardianLink(true).build();
    assertThat(ENGINE.decide(p, "Patient", "update", proxy).allowed()).isFalse();
    assertThat(ENGINE.decide(p, "Patient", "update", guardian).allowed()).isTrue();
  }

  @Test
  void unlinkedUserIsDeniedOwnAccess() {
    var p = new Principal("s", Set.of("patient"), null);
    assertThat(ENGINE.decide(p, "Patient", "read", ResourceContext.empty()).allowed()).isFalse();
  }

  @Test
  void multipleRolesUseAnyMatchingRule() {
    var p = new Principal("s", Set.of("patient", "nurse"), "x");
    assertThat(ENGINE.decide(p, "Slot", "create", ResourceContext.empty()).allowed()).isTrue();
  }

  @Test
  void loaderRejectsUnsafeMatrix() {
    String yaml =
        """
        roles: [admin]
        purposes: [TREAT]
        predicates: { own: x }
        resources: { Patient: { clinical: true, actions: [read] } }
        denyAll: []
        rules:
          - { id: BAD, resource: Patient, action: read, role: admin, when: [nope] }
        """;
    assertThatThrownBy(() -> AuthzMatrix.parse(yaml))
        .isInstanceOfSatisfying(
            AuthzMatrixException.class,
            e ->
                assertThat(e.errors())
                    .anyMatch(s -> s.contains("lâm sàng"))
                    .anyMatch(s -> s.contains("predicate lạ")));
  }

  @Test
  void loaderReportsMissingRuleDuplicateIdAndDenyAllViolation() {
    String yaml =
        """
        roles: [nurse, dpo]
        purposes: [TREAT]
        predicates: { own: x }
        resources: { Slot: { clinical: false, actions: [read, block] }, Audit: { clinical: false, actions: [delete] } }
        denyAll: [ { resource: Audit, action: delete } ]
        rules:
          - { id: A, resource: Slot, action: read, role: nurse, when: [] }
          - { id: A, resource: Audit, action: delete, role: dpo, when: [] }
        """;
    assertThatThrownBy(() -> AuthzMatrix.parse(yaml))
        .isInstanceOfSatisfying(
            AuthzMatrixException.class,
            e ->
                assertThat(e.errors())
                    .anyMatch(s -> s.contains("id trùng"))
                    .anyMatch(s -> s.contains("denyAll"))
                    .anyMatch(s -> s.contains("(Slot,block)")));
  }

  @Test
  void embeddedMatrixEqualsTheContractFile() throws Exception {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("contracts/authz/matrix.yaml"))) {
      dir = dir.getParent();
    }
    assertThat(dir).isNotNull();
    var onDisk = AuthzMatrix.parse(Files.readString(dir.resolve("contracts/authz/matrix.yaml")));
    assertThat(MATRIX.rules().stream().map(AuthzRule::id))
        .containsExactlyElementsOf(onDisk.rules().stream().map(AuthzRule::id).toList());
  }

  static Principal principal(String role) {
    return new Principal("subject-1", Set.of(role), "me");
  }

  /**
   * Ngữ cảnh sao cho ĐÚNG các predicate trong {@code holding} thỏa, mọi predicate khác không thỏa.
   * {@code care} và {@code not-care} loại trừ nhau: {@code notCareFails=true} dựng ngữ cảnh bác sĩ
   * CÓ quan hệ điều trị.
   */
  static ResourceContext ctx(Set<String> holding, boolean notCareFails) {
    return ResourceContext.builder()
        .patientId(holding.contains(Predicates.OWN) ? "me" : "someone-else")
        .linkedProfile(holding.contains(Predicates.LINKED))
        .linkedRecords(holding.contains(Predicates.LINKED_RECORDS))
        .guardianLink(holding.contains(Predicates.GUARDIAN))
        .practitionerId(holding.contains(Predicates.SELF_PRACTITIONER) ? "me" : "someone-else")
        .hasCareRelationship(holding.contains(Predicates.CARE) || notCareFails)
        .hasActiveBreakGlass(holding.contains(Predicates.BREAK_GLASS))
        .releasedToPatient(holding.contains(Predicates.RELEASED))
        .encounterFinished(
            holding.contains(Predicates.ENCOUNTER_FINISHED)
                ? Boolean.TRUE
                : holding.contains(Predicates.UNFINISHED_ENCOUNTER) ? Boolean.FALSE : null)
        .appointmentStart(
            holding.contains(Predicates.BEFORE_2H)
                ? NOW.plusSeconds(3 * 3600)
                : NOW.plusSeconds(3600))
        .build();
  }
}
