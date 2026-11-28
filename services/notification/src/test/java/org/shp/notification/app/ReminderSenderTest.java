package org.shp.notification.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.notification.infra.MongoReminderWorkQueue;

class ReminderSenderTest {

  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

  private static Document claimed(int attempt) {
    return new Document("_id", "reminder-1")
        .append("status", "sending")
        .append("attempts", attempt)
        .append("lockedUntil", Date.from(NOW.plusSeconds(120)));
  }

  private static NotificationMessage message(String channel) {
    return new NotificationMessage("fake-recipient", "Reminder", "Appointment reminder");
  }

  private static NotificationChannel channel(
      String name, NotificationResult result, AtomicInteger calls) {
    return new NotificationChannel() {
      @Override
      public String channelName() {
        return name;
      }

      @Override
      public NotificationResult send(NotificationMessage message) {
        calls.incrementAndGet();
        return result;
      }
    };
  }

  @Test
  void primaryFailureFallsBackAndStopsAfterSuccess() {
    MongoReminderWorkQueue queue = mock(MongoReminderWorkQueue.class);
    when(queue.finishAttempt(any(), anyList(), any(), eq(true), eq(false))).thenReturn(true);

    AtomicInteger pushCalls = new AtomicInteger();
    AtomicInteger emailCalls = new AtomicInteger();
    AtomicInteger smsCalls = new AtomicInteger();

    ReminderSender sender =
        new ReminderSender(
            queue,
            List.of(
                channel("push", NotificationResult.failure("provider-timeout"), pushCalls),
                channel("email", NotificationResult.success("email-123"), emailCalls),
                channel("sms", NotificationResult.success("sms-123"), smsCalls)),
            new SimpleMeterRegistry(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertTrue(
        sender.sendClaimed(
            claimed(1), List.of("push", "email", "sms"), ReminderSenderTest::message));

    assertEquals(1, pushCalls.get());
    assertEquals(1, emailCalls.get());
    assertEquals(0, smsCalls.get());

    verify(queue)
        .finishAttempt(any(), argThat(list -> list.size() == 2), any(), eq(true), eq(false));
  }

  @Test
  void fifthFailureFinishesAsDeadLetter() {
    MongoReminderWorkQueue queue = mock(MongoReminderWorkQueue.class);
    when(queue.finishAttempt(any(), anyList(), any(), eq(false), eq(false))).thenReturn(true);

    ReminderSender sender =
        new ReminderSender(
            queue,
            List.of(
                channel(
                    "push", NotificationResult.failure("provider-timeout"), new AtomicInteger())),
            new SimpleMeterRegistry(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertTrue(sender.sendClaimed(claimed(5), List.of("push"), ReminderSenderTest::message));

    verify(queue)
        .finishAttempt(
            argThat(doc -> doc.getInteger("attempts") == 5),
            argThat(list -> list.size() == 1 && list.get(0).getInteger("attemptNo") == 5),
            any(),
            eq(false),
            eq(false));
  }

  @Test
  void rejectedStaleWorkerDoesNotReportSuccess() {
    MongoReminderWorkQueue queue = mock(MongoReminderWorkQueue.class);

    ReminderSender sender =
        new ReminderSender(
            queue,
            List.of(channel("push", NotificationResult.success(null), new AtomicInteger())),
            new SimpleMeterRegistry(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertFalse(sender.sendClaimed(claimed(2), List.of("push"), ReminderSenderTest::message));
  }

  @Test
  void messagePreparationFailureIsNotTreatedAsProviderFailure() {
    MongoReminderWorkQueue queue = mock(MongoReminderWorkQueue.class);
    AtomicInteger providerCalls = new AtomicInteger();

    ReminderSender sender =
        new ReminderSender(
            queue,
            List.of(channel("push", NotificationResult.success(null), providerCalls)),
            new SimpleMeterRegistry(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThrows(
        IllegalStateException.class,
        () ->
            sender.sendClaimed(
                claimed(1),
                List.of("push"),
                channelName -> {
                  throw new IllegalStateException("Contact unavailable");
                }));

    assertEquals(0, providerCalls.get());
    verify(queue, never()).finishAttempt(any(), anyList(), any(), anyBoolean(), anyBoolean());
  }

  @Test
  void recordsLatencyFromDueAtToSentTaggedByChannelAndKind() {
    MongoReminderWorkQueue queue = mock(MongoReminderWorkQueue.class);
    when(queue.finishAttempt(any(), anyList(), any(), eq(true), eq(false))).thenReturn(true);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    ReminderSender sender =
        new ReminderSender(
            queue,
            List.of(channel("push", NotificationResult.success("push-1"), new AtomicInteger())),
            registry,
            Clock.fixed(NOW, ZoneOffset.UTC));

    Document doc =
        claimed(1).append("kind", "t-6h").append("dueAt", Date.from(NOW.minusSeconds(90)));

    assertTrue(sender.sendClaimed(doc, List.of("push"), ReminderSenderTest::message));

    io.micrometer.core.instrument.Timer timer =
        registry
            .get("shp.notification.send.latency")
            .tags("channel", "push", "kind", "t-6h")
            .timer();
    assertEquals(1, timer.count());
    assertEquals(90.0, timer.totalTime(java.util.concurrent.TimeUnit.SECONDS), 0.001);
    assertEquals(
        2,
        registry
            .get("shp.notification.send.latency")
            .meters()
            .iterator()
            .next()
            .getId()
            .getTags()
            .size());
  }

  @Test
  void doesNotRecordLatencyWhenSendFails() {
    MongoReminderWorkQueue queue = mock(MongoReminderWorkQueue.class);
    when(queue.finishAttempt(any(), anyList(), any(), eq(false), anyBoolean())).thenReturn(true);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    ReminderSender sender =
        new ReminderSender(
            queue,
            List.of(
                channel(
                    "push", NotificationResult.failure("provider-timeout"), new AtomicInteger())),
            registry,
            Clock.fixed(NOW, ZoneOffset.UTC));

    Document doc =
        claimed(1).append("kind", "t-6h").append("dueAt", Date.from(NOW.minusSeconds(90)));
    sender.sendClaimed(doc, List.of("push"), ReminderSenderTest::message);

    assertTrue(registry.find("shp.notification.send.latency").timers().isEmpty());
  }

  @Test
  void aRetriedReminderIsMeasuredFromItsOriginalScheduledTime() {
    MongoReminderWorkQueue queue = mock(MongoReminderWorkQueue.class);
    when(queue.finishAttempt(any(), anyList(), any(), eq(true), eq(false))).thenReturn(true);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    ReminderSender sender =
        new ReminderSender(
            queue,
            List.of(channel("push", NotificationResult.success("push-2"), new AtomicInteger())),
            registry,
            Clock.fixed(NOW, ZoneOffset.UTC));

    // Due at 11:44, failed twice; the backoff moved dueAt to just before the third attempt.
    Document doc =
        claimed(3)
            .append("kind", "t-24h")
            .append("scheduledAt", Date.from(NOW.minusSeconds(16 * 60)))
            .append("dueAt", Date.from(NOW.minusSeconds(1)));

    assertTrue(sender.sendClaimed(doc, List.of("push"), ReminderSenderTest::message));

    io.micrometer.core.instrument.Timer timer =
        registry
            .get("shp.notification.send.latency")
            .tags("channel", "push", "kind", "t-24h")
            .timer();
    assertEquals(1, timer.count());
    assertEquals(960.0, timer.totalTime(java.util.concurrent.TimeUnit.SECONDS), 0.001);
  }

  @Test
  void aDeadLetteredReminderIsCountedAsAFailedSend() {
    MongoReminderWorkQueue queue = mock(MongoReminderWorkQueue.class);
    when(queue.finishAttempt(any(), anyList(), any(), eq(false), anyBoolean())).thenReturn(true);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    ReminderSender sender =
        new ReminderSender(
            queue,
            List.of(
                channel(
                    "sms", NotificationResult.failure("provider-timeout"), new AtomicInteger())),
            registry,
            Clock.fixed(NOW, ZoneOffset.UTC));

    Document doc =
        claimed(5).append("kind", "t-2h").append("dueAt", Date.from(NOW.minusSeconds(90)));
    assertTrue(sender.sendClaimed(doc, List.of("sms"), ReminderSenderTest::message));

    var failed = registry.get("shp.notification.send.failed").counter();
    assertEquals(1.0, failed.count(), 0.0);
    assertEquals("sms", failed.getId().getTag("channel"));
    assertEquals("t-2h", failed.getId().getTag("kind"));
    assertEquals(2, failed.getId().getTags().size());
    assertTrue(registry.find("shp.notification.send.latency").timers().isEmpty());
  }

  @Test
  void aPermanentErrorIsCountedAsAFailedSendButATransientRetryIsNot() {
    MongoReminderWorkQueue queue = mock(MongoReminderWorkQueue.class);
    when(queue.finishAttempt(any(), anyList(), any(), eq(false), anyBoolean())).thenReturn(true);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    ReminderSender transientSender =
        new ReminderSender(
            queue,
            List.of(
                channel(
                    "push", NotificationResult.failure("provider-timeout"), new AtomicInteger())),
            registry,
            Clock.fixed(NOW, ZoneOffset.UTC));
    transientSender.sendClaimed(
        claimed(1).append("kind", "t-24h"), List.of("push"), ReminderSenderTest::message);
    assertTrue(registry.find("shp.notification.send.failed").counters().isEmpty());

    ReminderSender permanentSender =
        new ReminderSender(
            queue,
            List.of(
                channel(
                    "push", NotificationResult.failure("unregistered-token"), new AtomicInteger())),
            registry,
            Clock.fixed(NOW, ZoneOffset.UTC));
    permanentSender.sendClaimed(
        claimed(1).append("kind", "t-24h"), List.of("push"), ReminderSenderTest::message);
    assertEquals(
        1.0,
        registry
            .get("shp.notification.send.failed")
            .tags("channel", "push", "kind", "t-24h")
            .counter()
            .count(),
        0.0);
  }
}
