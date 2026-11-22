package org.shp.identity.keycloak.otp;

/** The code service could not be reached or answered unexpectedly; the step fails closed. */
public class OtpUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public OtpUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
