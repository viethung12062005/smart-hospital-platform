package org.shp.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NotificationMessageTest {

  @Test
  void rejectsMissingRecipient() {
    assertThatThrownBy(() -> new NotificationMessage(" ", "Subject", "Content"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recipient");
    assertThatThrownBy(() -> new NotificationMessage(null, "Subject", "Content"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recipient");
  }

  @Test
  void rejectsMissingContent() {
    assertThatThrownBy(() -> new NotificationMessage("test@example.com", "Subject", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("content");
    assertThatThrownBy(() -> new NotificationMessage("test@example.com", "Subject", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("content");
  }

  @Test
  void theSubjectIsOptional() {
    NotificationMessage message = new NotificationMessage("test@example.com", null, "Content");

    assertThat(message.subject()).isNull();
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
