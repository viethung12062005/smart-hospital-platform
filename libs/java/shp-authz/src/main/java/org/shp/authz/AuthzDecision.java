package org.shp.authz;

import java.util.List;

/**
 * Decision result. On denial, {@code denyReason} is used as {@code reason} in the audit (not
 * returned to the client).
 */
public record AuthzDecision(
    boolean allowed,
    String ruleId,
    String purpose,
    List<String> fields,
    List<String> hide,
    String denyReason) {

  public AuthzDecision {
    fields = List.copyOf(fields);
    hide = List.copyOf(hide);
  }

  public static AuthzDecision deny(String reason) {
    return new AuthzDecision(false, null, null, List.of(), List.of(), reason);
  }

  static AuthzDecision allow(AuthzRule rule) {
    return new AuthzDecision(true, rule.id(), rule.purpose(), rule.fields(), rule.hide(), null);
  }
}
