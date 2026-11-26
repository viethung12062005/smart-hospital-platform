package org.shp.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.shp.notification.app.NotificationMessage;
import org.shp.notification.app.NotificationResult;

class FcmPushChannelTest {

  @Test
  void sendsTokenTitleAndBodyAndReturnsProviderId() {
    AtomicReference<String> seen = new AtomicReference<>();
    FcmPushChannel channel =
        new FcmPushChannel((token, title, body) -> {
          seen.set(token + "|" + title + "|" + body);
          return "projects/x/messages/1";
        });

    NotificationResult result =
        channel.send(new NotificationMessage("synthetic-token", "Reminder", "Visit tomorrow"));

    assertThat(channel.channelName()).isEqualTo("push");
    assertThat(result.successful()).isTrue();
    assertThat(result.providerMessageId()).isEqualTo("projects/x/messages/1");
    assertThat(seen.get()).isEqualTo("synthetic-token|Reminder|Visit tomorrow");
  }

  @Test
  void mapsProviderFailureToStableErrorCode() {
    FcmPushChannel channel =
        new FcmPushChannel((token, title, body) -> {
          throw new IllegalStateException("boom");
        });

    NotificationResult result = channel.send(new NotificationMessage("t", "s", "c"));

    assertThat(result.successful()).isFalse();
    assertThat(result.errorCode()).isEqualTo("provider-error");
  }
}
