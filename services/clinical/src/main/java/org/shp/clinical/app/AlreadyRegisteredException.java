package org.shp.clinical.app;

/** The account already has a patient record (C1 answers 409 {@code already-registered}). */
public class AlreadyRegisteredException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public AlreadyRegisteredException() {
    super("already-registered");
  }
}
