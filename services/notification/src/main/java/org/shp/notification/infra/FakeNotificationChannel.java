package org.shp.notification.infra;

import java.util.Objects;
import java.util.Set;
import org.shp.notification.app.NotificationChannel;
import org.shp.notification.app.NotificationMessage;
import org.shp.notification.app.NotificationResult;

/**
 * Fake notification provider for development and tests.
 *
 * <p>This implementation never contacts an external provider.
 */
public class FakeNotificationChannel implements NotificationChannel {

  private static final Set<String> SUPPORTED_CHANNELS = Set.of("push", "sms", "email");

  private final String channelName;

  public FakeNotificationChannel(String channelName) {
    if (channelName == null || !SUPPORTED_CHANNELS.contains(channelName)) {
      throw new IllegalArgumentException("Channel must be push, sms or email");
    }

    this.channelName = channelName;
  }

  @Override
  public String channelName() {
    return channelName;
  }

  @Override
  public NotificationResult send(NotificationMessage message) {
    Objects.requireNonNull(message, "Notification message is required");

    // Deliberately simulate success without contacting an external provider.
    return NotificationResult.success(null);
  }
}
