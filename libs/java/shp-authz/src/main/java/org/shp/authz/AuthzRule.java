package org.shp.authz;

import java.util.List;

/**
 * One matrix row: (resource, action, role) is allowed when every predicate in {@code when} holds.
 *
 * @param purpose purposeOfUse attached to the decision (may be null)
 * @param fields fields that may be modified (empty = unrestricted)
 * @param hide fields that must be hidden from the result
 */
public record AuthzRule(
    String id,
    String resource,
    String action,
    String role,
    List<String> when,
    String purpose,
    List<String> fields,
    List<String> hide) {

  public AuthzRule {
    when = List.copyOf(when);
    fields = List.copyOf(fields);
    hide = List.copyOf(hide);
  }
}
