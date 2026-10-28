package org.shp.authz;

import java.util.List;

/**
 * Kết quả quyết định. Khi từ chối, {@code denyReason} dùng làm {@code reason} trong audit (không
 * trả cho client).
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
