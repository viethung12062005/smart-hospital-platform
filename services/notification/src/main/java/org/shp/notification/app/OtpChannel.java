package org.shp.notification.app;

/**
 * Delivery adapter for one-time codes (11 N8/N9). Phone numbers are tried through the adapters in
 * order (Zalo first, then SMS); email uses the email adapter. Implementations must never log the
 * destination or the code.
 */
public interface OtpChannel {

  /** Channel name reported to the caller, for example zalo, sms or email. */
  String name();

  /** True when this adapter can deliver to the kind of destination: phone or email. */
  boolean handles(String destinationKind);

  /**
   * Delivers the code.
   *
   * @throws OtpDeliveryException when the provider did not accept the message
   */
  void send(String destination, String code, String purpose, String locale)
      throws OtpDeliveryException;

  /** The provider rejected or could not deliver the message. */
  class OtpDeliveryException extends Exception {
    private static final long serialVersionUID = 1L;

    public OtpDeliveryException(String message) {
      super(message);
    }
  }
}
