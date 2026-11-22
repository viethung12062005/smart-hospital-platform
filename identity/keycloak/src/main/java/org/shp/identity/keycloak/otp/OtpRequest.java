package org.shp.identity.keycloak.otp;

/**
 * A request to send a code (N8). {@code destination} is the normalised phone number (E.164) or
 * email address; it is personal data and must never be logged.
 */
public record OtpRequest(OtpPurpose purpose, Channel channel, String destination, String locale) {
  @Override
  public String toString() {
    return "OtpRequest[purpose=" + purpose + ", channel=" + channel + ", locale=" + locale + "]";
  }
}
