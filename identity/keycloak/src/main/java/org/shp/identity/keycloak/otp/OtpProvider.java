package org.shp.identity.keycloak.otp;

import org.keycloak.provider.Provider;

/**
 * Sends and checks one-time codes that prove ownership of a phone number or email address
 * (ADR-0017). The code is generated, stored (hashed), expired and limited by the code service, not
 * by Keycloak: the production provider calls notification N8 and N9 (IAM-04).
 */
public interface OtpProvider extends Provider {

  /**
   * Sends a new code (N8).
   *
   * @throws OtpRateLimitedException too many codes for this destination or caller
   * @throws OtpUnavailableException the code service failed
   */
  OtpChallenge send(OtpRequest request);

  /**
   * Checks a code (N9); a valid code is consumed.
   *
   * @throws OtpUnavailableException the code service failed
   */
  OtpVerification verify(String challengeId, String code);

  @Override
  default void close() {}
}
