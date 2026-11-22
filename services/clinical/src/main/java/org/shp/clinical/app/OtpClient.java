package org.shp.clinical.app;

/**
 * notification's shared OTP service (11 §N8, N9; ADR-0018 §3): clinical asks it to send a code and
 * to check the code the user typed. The code itself never passes through clinical.
 */
public interface OtpClient {
  /**
   * N8: send a code to {@code destination} (E.164 phone number) and return its {@code challengeId}.
   *
   * @throws RateLimited when notification refuses because the number or caller hit its limit (429)
   */
  String send(String purpose, String destination, String locale);

  /** N9: check {@code code} against the challenge. */
  Result verify(String challengeId, String code);

  /** N9 outcome: right code, wrong code, or the challenge is locked after too many wrong codes. */
  enum Result {
    OK,
    INVALID,
    LOCKED
  }

  /** N8 answered 429 {@code otp-rate-limited}. */
  final class RateLimited extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RateLimited() {
      super("notification refused to send another code (429)");
    }
  }
}
