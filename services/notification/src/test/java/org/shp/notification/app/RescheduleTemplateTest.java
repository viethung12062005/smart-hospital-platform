package org.shp.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RescheduleTemplateTest {

  private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

  private static final Map<String, String> VARIABLES =
      Map.of(
          "previousStart", "03/12/2026 08:15",
          "startTime", "04/12/2026 08:15",
          "doctorName", "Demo Doctor",
          "place", "Demo Hospital",
          "specialty", "Cardiology",
          "manageLink", "https://example.test/appointments");

  @Test
  void rendersVietnameseCombinedNotification() {
    String content = renderer.renderReschedule("vi", VARIABLES);

    assertThat(content)
        .contains("đã được thay đổi")
        .contains("03/12/2026 08:15")
        .contains("04/12/2026 08:15")
        .contains("Demo Doctor")
        .doesNotContain("{previousStart}", "{startTime}");
  }

  @Test
  void rendersEnglishCombinedNotification() {
    String content = renderer.renderReschedule("en", VARIABLES);

    assertThat(content)
        .contains("successfully rescheduled")
        .contains("Previous time:")
        .contains("New time:")
        .contains("03/12/2026 08:15")
        .contains("04/12/2026 08:15")
        .doesNotContain("{previousStart}", "{startTime}");
  }

  @Test
  void rejectsMissingPreviousStart() {
    Map<String, String> variables = new HashMap<>(VARIABLES);
    variables.remove("previousStart");

    assertThatThrownBy(() -> renderer.renderReschedule("vi", variables))
        .isInstanceOf(NotificationTemplateException.class);
  }

  @Test
  void rejectsUnsupportedLocale() {
    assertThatThrownBy(() -> renderer.renderReschedule("fr", VARIABLES))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
