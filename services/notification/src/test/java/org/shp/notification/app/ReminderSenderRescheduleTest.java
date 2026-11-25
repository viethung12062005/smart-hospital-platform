package org.shp.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.notification.infra.FakeNotificationChannel;
import org.shp.notification.infra.MongoReminderWorkQueue;

/** NTF-03: the sender delivers the combined reschedule notice, not a plain confirmation. */
class ReminderSenderRescheduleTest {

  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
  private static final Map<String, String> VARIABLES =
      Map.of(
          "startTime", "04/12/2026 08:15",
          "doctorName", "Demo Doctor",
          "place", "Demo Hospital",
          "specialty", "Cardiology",
          "manageLink", "https://example.test/appointments");

  private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();
  private final List<NotificationMessage> delivered = new ArrayList<>();
  private final FakeNotificationChannel fake = new FakeNotificationChannel("push");

  private ReminderSender sender() {
    MongoReminderWorkQueue queue = mock(MongoReminderWorkQueue.class);
    when(queue.finishAttempt(any(), anyList(), any(), eq(true), eq(false))).thenReturn(true);
    NotificationChannel capturing =
        new NotificationChannel() {
          @Override
          public String channelName() {
            return fake.channelName();
          }

          @Override
          public NotificationResult send(NotificationMessage message) {
            delivered.add(message);
            return fake.send(message);
          }
        };
    return new ReminderSender(
        queue, List.of(capturing), new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static Document claimed(boolean reschedule) {
    Document doc =
        new Document("_id", "reminder-1")
            .append("status", "sending")
            .append("attempts", 1)
            .append("kind", "booking-confirmation");
    if (reschedule) {
      doc.append("noticeType", "reschedule")
          .append("previousStart", Date.from(Instant.parse("2026-12-03T01:15:00Z")));
    }
    return doc;
  }

  private boolean send(ReminderSender sender, Document claimed) {
    return sender.sendClaimed(
        claimed,
        List.of("push"),
        channel ->
            new NotificationMessage(
                "fake-recipient",
                "Appointment",
                sender.renderContent(claimed, renderer, "en", VARIABLES)));
  }

  @Test
  void rescheduledConfirmationDeliversTheCombinedNoticeWithThePreviousTime() {
    assertThat(send(sender(), claimed(true))).isTrue();

    assertThat(delivered).hasSize(1);
    assertThat(delivered.get(0).content())
        .contains("successfully rescheduled")
        .contains("Previous time: 03/12/2026 08:15")
        .contains("New time: 04/12/2026 08:15")
        .doesNotContain("has been confirmed");
  }

  @Test
  void ordinaryConfirmationIsUnchanged() {
    assertThat(send(sender(), claimed(false))).isTrue();

    assertThat(delivered).hasSize(1);
    assertThat(delivered.get(0).content())
        .contains("Your appointment has been confirmed.")
        .doesNotContain("Previous time")
        .doesNotContain("rescheduled");
  }
}
