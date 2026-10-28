package org.shp.authz;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Quyết định Allow/Deny theo {@link AuthzMatrix}. Mặc định TỪ CHỐI: chỉ cho phép khi có rule khớp
 * role và mọi predicate thỏa. Nhiều rule khớp: lấy rule đầu tiên theo thứ tự trong file (rule
 * thường đặt trước break-glass nên purpose là TREAT khi cả hai cùng thỏa).
 */
public final class AuthorizationEngine {
  static final Duration CANCEL_WINDOW = Duration.ofHours(2);

  private final AuthzMatrix matrix;
  private final Clock clock;

  public AuthorizationEngine(AuthzMatrix matrix, Clock clock) {
    this.matrix = Objects.requireNonNull(matrix);
    this.clock = Objects.requireNonNull(clock);
  }

  public AuthzDecision decide(
      Principal principal, String resource, String action, ResourceContext ctx) {
    Objects.requireNonNull(principal);
    Objects.requireNonNull(ctx);
    var rules = matrix.rulesFor(resource, action);
    if (rules.isEmpty()) {
      return AuthzDecision.deny("no-rule");
    }
    String firstUnmet = null;
    boolean roleMatched = false;
    for (AuthzRule rule : rules) {
      if (!principal.roles().contains(rule.role())) {
        continue;
      }
      roleMatched = true;
      String unmet =
          rule.when().stream().filter(p -> !holds(p, principal, ctx)).findFirst().orElse(null);
      if (unmet == null) {
        return AuthzDecision.allow(rule);
      }
      if (firstUnmet == null) {
        firstUnmet = unmet;
      }
    }
    if (!roleMatched) {
      return AuthzDecision.deny("role-not-allowed");
    }
    return AuthzDecision.deny(
        Predicates.CARE.equals(firstUnmet) ? "no-care-relationship" : "unmet-" + firstUnmet);
  }

  private boolean holds(String predicate, Principal p, ResourceContext c) {
    return switch (predicate) {
      case Predicates.OWN -> same(c.patientId(), p.fhirUserId());
      case Predicates.SELF_PRACTITIONER -> same(c.practitionerId(), p.fhirUserId());
      case Predicates.CARE -> c.hasCareRelationship();
      case Predicates.BREAK_GLASS -> c.hasActiveBreakGlass();
      case Predicates.NOT_CARE -> !c.hasCareRelationship();
      case Predicates.RELEASED -> Boolean.TRUE.equals(c.releasedToPatient());
      case Predicates.ENCOUNTER_FINISHED -> Boolean.TRUE.equals(c.encounterFinished());
      case Predicates.UNFINISHED_ENCOUNTER -> Boolean.FALSE.equals(c.encounterFinished());
      case Predicates.BEFORE_2H ->
          c.appointmentStart() != null
              && Duration.between(clock.instant(), c.appointmentStart()).compareTo(CANCEL_WINDOW)
                  >= 0;
      default -> false; // predicate lạ: từ chối (bộ nạp đã chặn từ trước)
    };
  }

  private static boolean same(String a, String b) {
    return a != null && a.equals(b);
  }
}
