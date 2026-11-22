package org.shp.identity.keycloak.otp;

/** Why a code is sent: the {@code purpose} of N8 (detailed-design 11) that Keycloak uses. */
public enum OtpPurpose {
  REGISTER("register");

  private final String wire;

  OtpPurpose(String wire) {
    this.wire = wire;
  }

  public String wire() {
    return wire;
  }
}
