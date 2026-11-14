package org.shp.authz;

import java.util.List;

/** Matrix violates a safety invariant; {@link #errors()} lists each error. */
public final class AuthzMatrixException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final List<String> errors;

  public AuthzMatrixException(List<String> errors) {
    super("Invalid authorization matrix: " + String.join("; ", errors));
    this.errors = List.copyOf(errors);
  }

  public List<String> errors() {
    return errors;
  }
}
