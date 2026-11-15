package org.shp.authz;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One generated authorization cell: a (resource, action, role) triple and the condition assignment
 * the decision is made under.
 *
 * <p>{@code holding} and {@code failing} name the predicates the cell fixes; a predicate in neither
 * set is irrelevant to the expected outcome. A cell is {@code allowed} exactly when a rule for the
 * triple has all of its {@code when} predicates in {@code holding}; otherwise it is a Deny (deny by
 * default, including roles with no rule and the {@code denyAll} actions).
 *
 * @param ruleId the rule that must produce the Allow, or null for a Deny
 */
public record AuthzTestCase(
    String resource,
    String action,
    String role,
    Set<String> holding,
    Set<String> failing,
    boolean allowed,
    String ruleId) {

  public AuthzTestCase {
    Objects.requireNonNull(resource, "resource");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(holding, "holding");
    Objects.requireNonNull(failing, "failing");
    holding = Collections.unmodifiableSet(new LinkedHashSet<>(holding));
    failing = Collections.unmodifiableSet(new LinkedHashSet<>(failing));
    if (!Collections.disjoint(holding, failing)) {
      throw new IllegalArgumentException("a predicate cannot both hold and fail: " + resource);
    }
    if (allowed == (ruleId == null)) {
      throw new IllegalArgumentException(
          "an Allow needs the matching rule id and a Deny must not carry one: " + resource);
    }
  }

  /** Human-readable label, used as the parameterized-test name. */
  public String description() {
    String conditions =
        holding.isEmpty() ? "no condition holds" : String.join(" + ", holding) + " hold";
    return resource
        + "."
        + action
        + " as "
        + role
        + " when "
        + conditions
        + " -> "
        + (allowed ? "ALLOW (" + ruleId + ")" : "DENY");
  }

  @Override
  public String toString() {
    return description();
  }
}
