package org.shp.authz;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Turns {@link AuthzMatrix} into the full authorization test grid — every declared (resource,
 * action) × every role × every feasible assignment of the predicates that pair uses — so a service
 * can run the complete matrix through {@link AuthorizationEngine#decide} instead of hand-writing
 * cases.
 *
 * <p>The matrix stays the single source of truth: add or change a rule in {@code
 * contracts/authz/matrix.yaml} and the generated Allow/Deny cases follow. A cell is Allow exactly
 * when a rule for its (resource, action, role) has every condition in {@link
 * AuthzTestCase#holding()}; every other cell is Deny, including roles without a rule and the {@code
 * denyAll} actions. The generator is pure data and has no JUnit dependency; a service test can feed
 * {@link #generate()} to a parameterized test, optionally narrowed with {@link
 * #generateFor(String)} to the resource it owns.
 */
public final class AuthzTestGenerator {
  private static final String SELF = "me";
  private static final String OTHER = "someone-else";
  private static final Duration THREE_HOURS = Duration.ofHours(3);
  private static final Duration ONE_HOUR = Duration.ofHours(1);

  private final AuthzMatrix matrix;

  public AuthzTestGenerator(AuthzMatrix matrix) {
    this.matrix = Objects.requireNonNull(matrix);
  }

  /** Generator over the matrix embedded in the library. */
  public static AuthzTestGenerator embedded() {
    return new AuthzTestGenerator(AuthzMatrix.loadEmbedded());
  }

  /** Every cell of the matrix, in matrix.yaml declaration order. */
  public List<AuthzTestCase> generate() {
    List<AuthzTestCase> cases = new ArrayList<>();
    matrix
        .actionsByResource()
        .forEach(
            (resource, actions) -> actions.forEach(a -> cases.addAll(generateFor(resource, a))));
    return List.copyOf(cases);
  }

  /** Every cell of one resource (a service's own endpoints), in declaration order. */
  public List<AuthzTestCase> generateFor(String resource) {
    List<String> actions = matrix.actionsByResource().get(resource);
    if (actions == null) {
      throw new IllegalArgumentException("unknown resource " + resource);
    }
    List<AuthzTestCase> cases = new ArrayList<>();
    actions.forEach(action -> cases.addAll(generateFor(resource, action)));
    return List.copyOf(cases);
  }

  /** Every cell of one (resource, action), in declaration order. */
  public List<AuthzTestCase> generateFor(String resource, String action) {
    List<String> actions = matrix.actionsByResource().get(resource);
    if (actions == null || !actions.contains(action)) {
      throw new IllegalArgumentException("unknown action " + resource + "." + action);
    }
    List<String> relevant = relevantPredicates(resource, action);
    List<AuthzTestCase> cases = new ArrayList<>();
    for (String role : matrix.roles()) {
      for (Set<String> holding : feasibleConditionSets(relevant)) {
        Set<String> failing = new LinkedHashSet<>(relevant);
        failing.removeAll(holding);
        String ruleId = matchingRule(resource, action, role, holding);
        cases.add(
            new AuthzTestCase(resource, action, role, holding, failing, ruleId != null, ruleId));
      }
    }
    return List.copyOf(cases);
  }

  /** Principal that realizes the cell's role; {@code fhirUser} is the identity built below. */
  public static Principal principal(AuthzTestCase testCase) {
    return new Principal("subject-1", Set.of(testCase.role()), SELF);
  }

  /**
   * Context that makes precisely the predicates in {@link AuthzTestCase#holding()} hold and the
   * ones in {@link AuthzTestCase#failing()} fail, for the predicates the (resource, action) pair
   * uses. Predicates outside both sets are unconstrained. {@code now} must be the engine's clock
   * instant.
   */
  public static ResourceContext context(AuthzTestCase testCase, Instant now) {
    Objects.requireNonNull(testCase, "testCase");
    Objects.requireNonNull(now, "now");
    Set<String> holding = testCase.holding();
    Set<String> failing = testCase.failing();
    return ResourceContext.builder()
        .patientId(holding.contains(Predicates.OWN) ? SELF : OTHER)
        .practitionerId(holding.contains(Predicates.SELF_PRACTITIONER) ? SELF : OTHER)
        .hasCareRelationship(
            holding.contains(Predicates.CARE) || failing.contains(Predicates.NOT_CARE))
        .hasActiveBreakGlass(holding.contains(Predicates.BREAK_GLASS))
        .releasedToPatient(truth(holding, failing, Predicates.RELEASED))
        .encounterFinished(encounterFinished(holding, failing))
        .appointmentStart(appointmentStart(holding, failing, now))
        .linkedProfile(holding.contains(Predicates.LINKED))
        .linkedRecords(holding.contains(Predicates.LINKED_RECORDS))
        .guardianLink(holding.contains(Predicates.GUARDIAN))
        .build();
  }

  /** Predicates used by at least one rule of the pair; the condition axis of its cells. */
  private List<String> relevantPredicates(String resource, String action) {
    return matrix.rulesFor(resource, action).stream()
        .flatMap(rule -> rule.when().stream())
        .distinct()
        .sorted()
        .toList();
  }

  /** First rule in file order whose role matches and whose conditions all hold. */
  private String matchingRule(String resource, String action, String role, Set<String> holding) {
    for (AuthzRule rule : matrix.rulesFor(resource, action)) {
      if (rule.role().equals(role) && holding.containsAll(rule.when())) {
        return rule.id();
      }
    }
    return null;
  }

  /**
   * All assignments of the relevant predicates that a real context can produce. {@code care} and
   * {@code not-care} are complements, so exactly one must hold; {@code encounter-finished} and
   * {@code unfinished-encounter} cannot both hold (both may fail when the encounter is unknown).
   */
  private static List<Set<String>> feasibleConditionSets(List<String> relevant) {
    List<Set<String>> sets = new ArrayList<>();
    for (int mask = 0; mask < (1 << relevant.size()); mask++) {
      Set<String> holding = new LinkedHashSet<>();
      for (int i = 0; i < relevant.size(); i++) {
        if ((mask & (1 << i)) != 0) {
          holding.add(relevant.get(i));
        }
      }
      if (feasible(holding, relevant)) {
        sets.add(Collections.unmodifiableSet(holding));
      }
    }
    return sets;
  }

  private static boolean feasible(Set<String> holding, List<String> relevant) {
    if (relevant.contains(Predicates.CARE) && relevant.contains(Predicates.NOT_CARE)) {
      boolean care = holding.contains(Predicates.CARE);
      boolean notCare = holding.contains(Predicates.NOT_CARE);
      if (care == notCare) {
        return false; // complements: one holds and the other fails, never both or neither
      }
    }
    return !(holding.contains(Predicates.ENCOUNTER_FINISHED)
        && holding.contains(Predicates.UNFINISHED_ENCOUNTER));
  }

  private static Boolean truth(Set<String> holding, Set<String> failing, String predicate) {
    if (holding.contains(predicate)) {
      return Boolean.TRUE;
    }
    if (failing.contains(predicate)) {
      return Boolean.FALSE;
    }
    return null;
  }

  private static Boolean encounterFinished(Set<String> holding, Set<String> failing) {
    if (holding.contains(Predicates.ENCOUNTER_FINISHED)) {
      return Boolean.TRUE;
    }
    if (holding.contains(Predicates.UNFINISHED_ENCOUNTER)) {
      return Boolean.FALSE;
    }
    if (failing.contains(Predicates.ENCOUNTER_FINISHED)) {
      return Boolean.FALSE;
    }
    if (failing.contains(Predicates.UNFINISHED_ENCOUNTER)) {
      return Boolean.TRUE;
    }
    return null;
  }

  private static Instant appointmentStart(Set<String> holding, Set<String> failing, Instant now) {
    if (holding.contains(Predicates.BEFORE_2H)) {
      return now.plus(THREE_HOURS);
    }
    if (failing.contains(Predicates.BEFORE_2H)) {
      return now.plus(ONE_HOUR);
    }
    return null;
  }
}
