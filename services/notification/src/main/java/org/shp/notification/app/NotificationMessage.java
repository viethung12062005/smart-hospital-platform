package org.shp.notification.app;

/**
 * Notification data passed to a channel at send time.
 *
 * <p>The recipient is transient delivery data. It must not be persisted in reminder documents or
 * written to logs.
 */
public record NotificationMessage(String recipient, String subject, String content) {

  public NotificationMessage {
    if (recipient == null || recipient.isBlank()) {
      throw new IllegalArgumentException("Notification recipient is required");
    }

    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("Notification content is required");
    }
  }

  /** Avoid exposing recipient or notification content through accidental logging. */
  @Override
  public String toString() {
    return "NotificationMessage[recipient=<redacted>, subject=<redacted>, content=<redacted>]";
  }
}
