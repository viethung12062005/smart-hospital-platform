package org.shp.identity.keycloak.otp;

/** The answer of N9 for one code. */
public enum OtpVerification {
  /** The code is right, unused and not expired; it cannot be used again. */
  VALID,
  /** Wrong, already used or expired. */
  INVALID,
  /** Too many wrong attempts: the challenge is locked for a while. */
  LOCKED
}
