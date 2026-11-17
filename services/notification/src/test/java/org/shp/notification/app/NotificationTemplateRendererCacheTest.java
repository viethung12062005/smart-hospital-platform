package org.shp.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.shp.notification.domain.ReminderKind;

class NotificationTemplateRendererCacheTest {

  private static final Map<String, String> VARIABLES =
      Map.of(
          "doctorName", "Dr. Nguyen",
          "startTime", "09:30 20/11/2026",
          "place", "Room 301");

  private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

  @Test
  void rendersTheSameTextEveryTimeAndFromManyThreadsAtOnce() throws Exception {
    String first = renderer.render(ReminderKind.T_24H, "en", VARIABLES);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<String>> calls = new ArrayList<>();
      for (int i = 0; i < 64; i++) {
        calls.add(() -> renderer.render(ReminderKind.T_24H, "en", VARIABLES));
      }
      for (Future<String> result : pool.invokeAll(calls)) {
        assertThat(result.get()).isEqualTo(first);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void differentKindsAndLocalesDoNotShareACacheEntry() {
    String en = renderer.render(ReminderKind.T_2H, "en", VARIABLES);
    String vi = renderer.render(ReminderKind.T_2H, "vi", VARIABLES);
    String t24 = renderer.render(ReminderKind.T_24H, "en", VARIABLES);

    assertThat(en).isNotEqualTo(vi).isNotEqualTo(t24);
  }

  @Test
  void aMissingTemplateIsReportedOnEveryCallNotCached() {
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> renderer.render(ReminderKind.T_6H_RISK, "vi", VARIABLES))
          .isInstanceOf(NotificationTemplateException.class)
          .hasMessageContaining("t-6h-risk.vi.txt");
    }
  }
}
