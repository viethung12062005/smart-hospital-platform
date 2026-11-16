package org.shp.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.shp.notification.domain.ReminderKind;

class NotificationTemplateRendererTest {

  private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

  private static final Map<String, String> VARIABLES =
      Map.of(
          "doctorName", "Dr. Nguyen",
          "startTime", "09:30 20/11/2026",
          "place", "Room 301");

  @Test
  void rendersAllAppointmentTemplatesInVietnameseAndEnglish() {
    for (ReminderKind kind :
        new ReminderKind[] {
          ReminderKind.BOOKING_CONFIRMATION, ReminderKind.T_24H, ReminderKind.T_2H
        }) {
      for (String locale : new String[] {"vi", "en"}) {
        String content = renderer.render(kind, locale, VARIABLES);

        assertThat(content)
            .contains("Dr. Nguyen")
            .contains("09:30 20/11/2026")
            .contains("Room 301")
            .doesNotContain("{doctorName}")
            .doesNotContain("{startTime}")
            .doesNotContain("{place}");
      }
    }
  }

  @Test
  void rendersVietnameseBookingConfirmation() {
    String content = renderer.render(ReminderKind.BOOKING_CONFIRMATION, "vi", VARIABLES);

    assertThat(content).contains("Lịch hẹn của bạn đã được xác nhận");
  }

  @Test
  void rendersEnglishBookingConfirmation() {
    String content = renderer.render(ReminderKind.BOOKING_CONFIRMATION, "en", VARIABLES);

    assertThat(content).contains("Your appointment has been confirmed");
  }

  @Test
  void rejectsUnsupportedLocale() {
    assertThatThrownBy(() -> renderer.render(ReminderKind.BOOKING_CONFIRMATION, "fr", VARIABLES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Locale");
  }

  @Test
  void rejectsMissingTemplateVariable() {
    Map<String, String> incompleteVariables =
        Map.of(
            "doctorName", "Dr. Nguyen",
            "startTime", "09:30 20/11/2026");

    assertThatThrownBy(
            () -> renderer.render(ReminderKind.BOOKING_CONFIRMATION, "vi", incompleteVariables))
        .isInstanceOf(NotificationTemplateException.class)
        .hasMessageContaining("place");
  }

  @Test
  void rejectsMissingTemplateForUnsupportedReminderKind() {
    assertThatThrownBy(() -> renderer.render(ReminderKind.T_6H_RISK, "vi", VARIABLES))
        .isInstanceOf(NotificationTemplateException.class)
        .hasMessageContaining("t-6h-risk.vi.txt");
  }
}
