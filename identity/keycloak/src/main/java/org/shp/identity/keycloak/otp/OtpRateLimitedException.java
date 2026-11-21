package org.shp.identity.keycloak.otp;

/** Sending was refused because the destination or the caller asked for too many codes. */
public class OtpRateLimitedException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public OtpRateLimitedException(String message) {
    super(message);
  }
}
