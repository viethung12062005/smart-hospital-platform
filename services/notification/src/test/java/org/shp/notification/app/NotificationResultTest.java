package org.shp.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NotificationResultTest {

  @Test
  void successCarriesTheProviderMessageIdAndNoError() {
    NotificationResult result = NotificationResult.success("provider-id-1");

    assertThat(result.successful()).isTrue();
    assertThat(result.providerMessageId()).isEqualTo("provider-id-1");
    assertThat(result.errorCode()).isNull();
  }

  @Test
  void failureCarriesTheErrorCodeAndNoProviderMessageId() {
    NotificationResult result = NotificationResult.failure("provider-timeout");

    assertThat(result.successful()).isFalse();
    assertThat(result.errorCode()).isEqualTo("provider-timeout");
    assertThat(result.providerMessageId()).isNull();
  }

  @Test
  void rejectsASuccessfulResultThatHasAnErrorCode() {
    assertThatThrownBy(() -> new NotificationResult(true, "provider-id-1", "provider-timeout"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("successful");
  }

  @Test
  void rejectsAFailedResultWithoutAnErrorCode() {
    assertThatThrownBy(() -> NotificationResult.failure(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("error code");
    assertThatThrownBy(() -> NotificationResult.failure("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("error code");
  }
}
