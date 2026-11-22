package org.shp.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.shp.notification.domain.ReminderKind;

class NotificationTemplateRendererTest {

  private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

  private static final Map<String, String> VARIABLES =
      Map.of(
          "doctorName", "Dr. Nguyen",
          "startTime", "09:30 20/11/2026",
          "place", "Room 301",
          "specialty", "Cardiology",
          "manageLink", "https://app.example.org/appointments/0192f8a4");

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
            .contains("Cardiology")
            .contains("https://app.example.org/appointments/0192f8a4")
            .doesNotContain("{specialty}")
            .doesNotContain("{manageLink}")
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
            "startTime", "09:30 20/11/2026",
            "specialty", "Cardiology",
            "manageLink", "https://app.example.org/appointments/0192f8a4");

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

  @Test
  void rendersValuesLiterallyEvenWhenTheyLookLikeTemplateSyntax() {
    Map<String, String> tricky =
        Map.of(
            "doctorName", "Dr. {place} $1 \\",
            "startTime", "09:30 20/11/2026",
            "place", "Room 301",
            "specialty", "Cardiology",
            "manageLink", "https://app.example.org/appointments/0192f8a4");

    String content = renderer.render(ReminderKind.T_2H, "en", tricky);

    assertThat(content).contains("Doctor: Dr. {place} $1 \\").contains("Location: Room 301");
  }

  @Test
  void rejectsABlankTemplateVariable() {
    Map<String, String> blank =
        Map.of(
            "doctorName", "Dr. Nguyen",
            "startTime", "09:30 20/11/2026",
            "place", " ",
            "specialty", "Cardiology",
            "manageLink", "https://app.example.org/appointments/0192f8a4");

    assertThatThrownBy(() -> renderer.render(ReminderKind.T_24H, "vi", blank))
        .isInstanceOf(NotificationTemplateException.class)
        .hasMessageContaining("place");
  }

  @Test
  void rejectsMissingArguments() {
    assertThatThrownBy(() -> renderer.render(null, "vi", VARIABLES))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> renderer.render(ReminderKind.T_2H, "vi", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> renderer.render(ReminderKind.T_2H, null, VARIABLES))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** AC-SN.1 at template level: only the three designed placeholders, no clinical wording. */
  @Test
  void everyAppointmentTemplateUsesOnlyTheDesignedPlaceholdersAndNoClinicalWording()
      throws IOException {
    Set<String> designed = Set.of("doctorName", "startTime", "place", "specialty", "manageLink");
    Pattern placeholder = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");
    // 11-api-notification-audit-ml section 1: no diagnosis, visit reason or results in the content.
    Set<String> forbidden =
        Set.of("diagnosis", "reason", "result", "chẩn đoán", "lý do khám", "kết quả");

    for (ReminderKind kind :
        new ReminderKind[] {
          ReminderKind.BOOKING_CONFIRMATION, ReminderKind.T_24H, ReminderKind.T_2H
        }) {
      for (String locale : new String[] {"vi", "en"}) {
        String name = "templates/" + kind.value() + "." + locale + ".txt";
        String text;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
          assertThat(in).as(name).isNotNull();
          text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Set<String> used = new HashSet<>();
        Matcher matcher = placeholder.matcher(text);
        while (matcher.find()) {
          used.add(matcher.group(1));
        }

        assertThat(used).as(name).isEqualTo(designed);
        for (String word : forbidden) {
          assertThat(text.toLowerCase(Locale.ROOT)).as(name).doesNotContain(word);
        }
      }
    }
  }
}
