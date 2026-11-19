package org.shp.clinical.app;

/** The requested account roles do not match the linked profile (C21, 15 §8). Maps to 422. */
public class RoleMismatchException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public RoleMismatchException(String message) {
    super(message);
  }
}
