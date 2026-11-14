package org.shp.servicedefaults.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;

/**
 * TC-SEC-11 / NFR-O4: a log message must not contain PII/PHI. Verifies that PiiMaskingConverter
 * masks email, phone, CCCD/CMND, BHYT, date and UUID in the message before it is written.
 */
class PiiMaskingConverterTest {

  private final PiiMaskingConverter converter = new PiiMaskingConverter();

  private String mask(String message) {
    ILoggingEvent event = mock(ILoggingEvent.class);
    when(event.getFormattedMessage()).thenReturn(message);
    return converter.convert(event);
  }

  @Test
  void masksAllPiiCategoriesInOneMessage() {
    String message =
        "Patient born 12/03/1980, phone 0912 345 678, "
            + "email an.nguyen@example.com, national id 001080012345, "
            + "insurance card DN4790123456789, record id 550e8400-e29b-41d4-a716-446655440000";

    String masked = mask(message);

    assertThat(masked)
        .doesNotContain("an.nguyen@example.com")
        .doesNotContain("0912 345 678")
        .doesNotContain("001080012345")
        .doesNotContain("DN4790123456789")
        .doesNotContain("12/03/1980")
        .doesNotContain("550e8400-e29b-41d4-a716-446655440000")
        .contains("[REDACTED]");
  }

  @Test
  void leavesOrdinaryTextIntact() {
    String message = "Appointment booked successfully, no sensitive data here.";

    assertThat(mask(message)).isEqualTo(message);
  }

  @Test
  void masksPhoneWithVietnamCountryCode() {
    assertThat(mask("Contact +84912345678 for support"))
        .doesNotContain("+84912345678")
        .contains("[REDACTED]");
  }

  @Test
  void masksNineDigitCmnd() {
    assertThat(mask("Legacy CMND 012345678")).doesNotContain("012345678").contains("[REDACTED]");
  }

  @Test
  void returnsNullForNullMessage() {
    assertThat(converter.convert(mock(ILoggingEvent.class))).isNull();
  }
}
