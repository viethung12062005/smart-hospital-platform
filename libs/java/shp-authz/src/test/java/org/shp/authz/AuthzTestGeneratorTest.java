package org.shp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the generated grid from {@code contracts/authz/matrix.yaml} through {@link
 * AuthorizationEngine#decide} and checks that the generator really covers every cell: every
 * declared (resource, action) × role × feasible condition assignment, with an Allow for each rule
 * and a Deny for every other combination.
 */
class AuthzTestGeneratorTest {
  static final AuthzMatrix MATRIX = AuthzMatrix.loadEmbedded();
  static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  static final AuthorizationEngine ENGINE =
      new AuthorizationEngine(MATRIX, Clock.fixed(NOW, ZoneOffset.UTC));
  static final AuthzTestGenerator GENERATOR = new AuthzTestGenerator(MATRIX);
  static final List<AuthzTestCase> CASES = GENERATOR.generate();

  static Stream<AuthzTestCase> cases() {
    return CASES.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void generatedCaseMatchesTheEngine(AuthzTestCase testCase) {
    AuthzDecision decision =
        ENGINE.decide(
            AuthzTestGenerator.principal(testCase),
            testCase.resource(),
            testCase.action(),
            AuthzTestGenerator.context(testCase, NOW));
    assertThat(decision.allowed()).as("%s", testCase).isEqualTo(testCase.allowed());
    if (testCase.allowed()) {
      assertThat(decision.ruleId()).as("%s", testCase).isEqualTo(testCase.ruleId());
    } else {
      assertThat(decision.denyReason()).as("%s", testCase).isNotBlank();
    }
  }

  @Test
  void everyDeclaredActionAndRoleGetsEveryFeasibleConditionCell() {
    for (var entry : MATRIX.actionsByResource().entrySet()) {
      for (String action : entry.getValue()) {
        List<String> relevant = relevantPredicates(entry.getKey(), action);
        for (String role : MATRIX.roles()) {
          List<AuthzTestCase> cells =
              CASES.stream()
                  .filter(c -> c.resource().equals(entry.getKey()))
                  .filter(c -> c.action().equals(action))
                  .filter(c -> c.role().equals(role))
                  .toList();
          String cell = entry.getKey() + "." + action + " as " + role;
          assertThat(cells).as(cell).hasSize(feasibleConditionCount(relevant));
          assertThat(cells.stream().map(AuthzTestCase::holding).distinct().count())
              .as("%s: no duplicate condition cell", cell)
              .isEqualTo(cells.size());
        }
      }
    }
  }

  @Test
  void everyRuleHasAnAllowCaseAndADenyCasePerCondition() {
    for (AuthzRule rule : MATRIX.rules()) {
      assertThat(CASES)
          .filteredOn(c -> c.allowed() && rule.id().equals(c.ruleId()))
          .as("%s: needs an Allow case", rule.id())
          .isNotEmpty();
      for (String condition : rule.when()) {
        Set<String> otherConditions =
            rule.when().stream()
                .filter(predicate -> !predicate.equals(condition))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(CASES)
            .filteredOn(c ->
                !c.allowed()
                    && c.resource().equals(rule.resource())
                    && c.action().equals(rule.action())
                    && c.role().equals(rule.role())
                    && c.holding().containsAll(otherConditions)
                    && c.failing().contains(condition))
            .as("%s: needs a Deny case when %s fails", rule.id(), condition)
            .isNotEmpty();
      }
    }
  }

  @Test
  void everyMatrixRuleIsExercisedByAnAllowCase() {
    assertThat(CASES.stream().filter(AuthzTestCase::allowed).map(AuthzTestCase::ruleId).distinct())
        .containsExactlyInAnyOrderElementsOf(
            MATRIX.rules().stream().map(AuthzRule::id).collect(Collectors.toSet()));
  }

  @Test
  void rolesWithoutARuleAreDeniedUnderEveryCondition() {
    int checkedPairs = 0;
    for (var entry : MATRIX.actionsByResource().entrySet()) {
      for (String action : entry.getValue()) {
        Set<String> rolesWithRule =
            MATRIX.rulesFor(entry.getKey(), action).stream()
                .map(AuthzRule::role)
                .collect(Collectors.toSet());
        for (String role : MATRIX.roles()) {
          if (rolesWithRule.contains(role)) {
            continue;
          }
          checkedPairs++;
          assertThat(CASES)
              .filteredOn(c ->
                  c.resource().equals(entry.getKey())
                      && c.action().equals(action)
                      && c.role().equals(role))
              .as("%s.%s as %s", entry.getKey(), action, role)
              .isNotEmpty()
              .allMatch(c -> !c.allowed());
        }
      }
    }
    assertThat(checkedPairs)
        .as("the matrix must exercise default deny (for example the Audit denyAll actions)")
        .isPositive();
  }

  @Test
  void generatedCasesContainAllowAndDeny() {
    assertThat(CASES).anyMatch(AuthzTestCase::allowed).anyMatch(c -> !c.allowed());
  }

  @Test
  void generationIsDeterministic() {
    assertThat(GENERATOR.generate()).isEqualTo(CASES);
  }

  @Test
  void generatorCanBeNarrowedToTheResourcesAServiceOwns() {
    List<AuthzTestCase> scheduling = GENERATOR.generateFor("Appointment");
    assertThat(scheduling).isNotEmpty().allMatch(c -> c.resource().equals("Appointment"));
    assertThat(GENERATOR.generateFor("Appointment", "book"))
        .isNotEmpty()
        .allMatch(c -> c.resource().equals("Appointment") && c.action().equals("book"));
    assertThatThrownBy(() -> GENERATOR.generateFor("Nope"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GENERATOR.generateFor("Appointment", "teleport"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void embeddedGeneratorUsesTheContractMatrix() {
    assertThat(AuthzTestGenerator.embedded().generate()).isEqualTo(CASES);
  }

  @Test
  void caseDescriptionNamesTheDecision() {
    AuthzTestCase allow =
        new AuthzTestCase("Patient", "read", "nurse", Set.of(), Set.of(), true, "PAT-READ-NUR");
    AuthzTestCase deny =
        new AuthzTestCase("Patient", "read", "admin", Set.of(), Set.of(), false, null);
    assertThat(allow.description()).contains("ALLOW (PAT-READ-NUR)");
    assertThat(deny.description()).contains("DENY");
    assertThat(deny.toString()).isEqualTo(deny.description());
  }

  @Test
  void invalidCaseIsRejected() {
    assertThatThrownBy(
            () -> new AuthzTestCase("Patient", "read", "nurse", Set.of(), Set.of(), true, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Allow needs");
    assertThatThrownBy(() ->
            new AuthzTestCase(
                "Patient", "read", "nurse", Set.of("own"), Set.of("own"), false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("both hold and fail");
  }

  @Test
  void contextHelperRealizesEachGeneratedCondition() {
    for (AuthzTestCase testCase : CASES) {
      ResourceContext context = AuthzTestGenerator.context(testCase, NOW);
      assertThat(context.appointmentStart() != null)
          .as("%s: appointmentStart is only set for before-2h", testCase)
          .isEqualTo(
              testCase.holding().contains(Predicates.BEFORE_2H)
                  || testCase.failing().contains(Predicates.BEFORE_2H));
      assertThat(context.hasCareRelationship())
          .as("%s: care/not-care realize each other's negation", testCase)
          .isEqualTo(
              testCase.holding().contains(Predicates.CARE)
                  || testCase.failing().contains(Predicates.NOT_CARE));
    }
  }

  /** Independent count of the realizable subsets of the pair's condition axis. */
  private static int feasibleConditionCount(List<String> relevant) {
    int count = 0;
    for (int mask = 0; mask < (1 << relevant.size()); mask++) {
      Set<String> holding = new HashSet<>();
      for (int i = 0; i < relevant.size(); i++) {
        if ((mask & (1 << i)) != 0) {
          holding.add(relevant.get(i));
        }
      }
      boolean careConflict =
          relevant.contains(Predicates.CARE)
              && relevant.contains(Predicates.NOT_CARE)
              && holding.contains(Predicates.CARE) == holding.contains(Predicates.NOT_CARE);
      boolean encounterConflict =
          holding.contains(Predicates.ENCOUNTER_FINISHED)
              && holding.contains(Predicates.UNFINISHED_ENCOUNTER);
      if (!careConflict && !encounterConflict) {
        count++;
      }
    }
    return count;
  }

  private static List<String> relevantPredicates(String resource, String action) {
    return MATRIX.rulesFor(resource, action).stream()
        .flatMap(rule -> rule.when().stream())
        .distinct()
        .sorted()
        .toList();
  }
}
