package org.shp.authz;

import java.util.List;

/** Ma trận vi phạm bất biến an toàn; {@link #errors()} liệt kê từng lỗi. */
public final class AuthzMatrixException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final List<String> errors;

  public AuthzMatrixException(List<String> errors) {
    super("Ma trận phân quyền không hợp lệ: " + String.join("; ", errors));
    this.errors = List.copyOf(errors);
  }

  public List<String> errors() {
    return errors;
  }
}
