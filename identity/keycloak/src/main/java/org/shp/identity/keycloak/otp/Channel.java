package org.shp.identity.keycloak.otp;

/** Where a code is sent: a phone number (Zalo first, then SMS) or an email address (ADR-0017). */
public enum Channel {
  PHONE("phone"),
  EMAIL("email");

  private final String wire;

  Channel(String wire) {
    this.wire = wire;
  }

  /** The value of {@code channelHint} in N8 (detailed-design 11). */
  public String wire() {
    return wire;
  }
}
