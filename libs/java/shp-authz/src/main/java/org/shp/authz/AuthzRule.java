package org.shp.authz;

import java.util.List;

/**
 * Một dòng của ma trận: (resource, action, role) được phép khi mọi predicate trong {@code when}
 * thỏa.
 *
 * @param purpose purposeOfUse gắn với quyết định (có thể null)
 * @param fields các trường được phép sửa (rỗng = không giới hạn)
 * @param hide các trường phải ẩn khỏi kết quả
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
