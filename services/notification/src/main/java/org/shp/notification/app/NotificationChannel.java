package org.shp.notification.app;

/**
 * Abstraction for an outbound notification channel.
 *
 * <p>Implementations may connect to push, SMS or email providers.
 */
public interface NotificationChannel {

  /** Returns the channel identifier: push, sms or email. */
  String channelName();

  /** Sends a notification and returns the provider result. */
  NotificationResult send(NotificationMessage message);
}
