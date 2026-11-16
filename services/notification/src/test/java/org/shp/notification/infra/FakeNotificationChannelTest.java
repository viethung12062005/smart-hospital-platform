package org.shp.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.shp.notification.app.NotificationMessage;

class FakeNotificationChannelTest {

  @Test
  void supportsPushSmsAndEmail() {
    for (String channelName : new String[] {"push", "sms", "email"}) {
      FakeNotificationChannel channel = new FakeNotificationChannel(channelName);

      assertThat(channel.channelName()).isEqualTo(channelName);
    }
  }

  @Test
  void simulatesSuccessfulDeliveryWithoutExternalProvider() {
    FakeNotificationChannel channel = new FakeNotificationChannel("email");
    NotificationMessage message =
        new NotificationMessage("test@example.com", "Appointment reminder", "Reminder content");

    var result = channel.send(message);

    assertThat(result.successful()).isTrue();
    assertThat(result.providerMessageId()).isNull();
    assertThat(result.errorCode()).isNull();
  }

  @Test
  void rejectsUnsupportedChannel() {
    assertThatThrownBy(() -> new FakeNotificationChannel("whatsapp"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("push, sms or email");
  }

  @Test
  void rejectsMissingRecipient() {
    assertThatThrownBy(() -> new NotificationMessage(" ", "Subject", "Content"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recipient");
  }

  @Test
  void redactsSensitiveMessageFieldsFromToString() {
    NotificationMessage message =
        new NotificationMessage("secret@example.com", "Private subject", "Private content");

    assertThat(message.toString())
        .doesNotContain("secret@example.com")
        .doesNotContain("Private subject")
        .doesNotContain("Private content")
        .contains("<redacted>");
  }
}
