package org.shp.notification.app;

/** Result returned by a notification channel after a send attempt. */
public record NotificationResult(boolean successful, String providerMessageId, String errorCode) {

  public NotificationResult {
    if (successful && errorCode != null) {
      throw new IllegalArgumentException("A successful result must not contain an error code");
    }

    if (!successful && (errorCode == null || errorCode.isBlank())) {
      throw new IllegalArgumentException("A failed result must contain an error code");
    }
  }

  public static NotificationResult success(String providerMessageId) {
    return new NotificationResult(true, providerMessageId, null);
  }

  public static NotificationResult failure(String errorCode) {
    return new NotificationResult(false, null, errorCode);
  }
}
