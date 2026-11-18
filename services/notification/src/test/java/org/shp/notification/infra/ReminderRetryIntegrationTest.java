package org.shp.notification.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

@Testcontainers
class ReminderRetryIntegrationTest {

  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

  private MongoTemplate mongo;
  private MongoReminderWorkQueue queue;

  @BeforeEach
  void setUp() {
    mongo =
        new MongoTemplate(
            com.mongodb.client.MongoClients.create(MONGO.getReplicaSetUrl()),
            "notification_ntf07_test");

    mongo.dropCollection("reminders");
    queue = new MongoReminderWorkQueue(mongo);
  }

  private Document reminder(String id, int attempts, String status, Instant dueAt) {
    Instant now = Instant.now();

    return new Document("_id", id)
        .append("subjectRef", "Appointment/" + id)
        .append("patientId", "patient-1")
        .append("kind", "t-24h")
        .append("appointmentStart", Date.from(now.plusSeconds(86400)))
        .append("dueAt", Date.from(dueAt))
        .append("status", status)
        .append("attempts", attempts)
        .append("lockedUntil", null)
        .append("deliveries", List.of())
        .append("createdAt", Date.from(now))
        .append("updatedAt", Date.from(now));
  }

  @Test
  void fifthFailureIsDeadLettered() {
    mongo.insert(
        reminder("fifth-failure", 4, "scheduled", Instant.now().minusSeconds(60)), "reminders");

    Document claimed = queue.claimNext().orElseThrow();

    assertEquals(5, claimed.getInteger("attempts"));

    Document failedDelivery =
        new Document("attemptNo", 5)
            .append("channel", "push")
            .append("provider", "fake")
            .append("outcome", "failed")
            .append("errorCode", "provider-timeout")
            .append("latencyMs", 10L)
            .append("attemptedAt", new Date());

    assertTrue(queue.finishAttempt(claimed, List.of(failedDelivery), Instant.now(), false, false));

    Document stored = mongo.findById("fifth-failure", Document.class, "reminders");

    assertNotNull(stored);
    assertEquals("dead-lettered", stored.getString("status"));
    assertEquals(5, stored.getInteger("attempts"));
    assertNotNull(stored.getDate("completedAt"));
    assertTrue(queue.claimNext().isEmpty());
  }

  @Test
  void expiredFifthAttemptCannotBeClaimedAgain() {
    Document reminder = reminder("expired-fifth", 5, "sending", Instant.now().minusSeconds(60));

    reminder.put("lockedUntil", Date.from(Instant.now().minusSeconds(120)));

    mongo.insert(reminder, "reminders");

    assertEquals(1, queue.recoverExpiredClaims());

    Document stored = mongo.findById("expired-fifth", Document.class, "reminders");

    assertNotNull(stored);
    assertEquals("dead-lettered", stored.getString("status"));
    assertEquals(5, stored.getInteger("attempts"));
    assertNotNull(stored.getDate("completedAt"));
    assertTrue(queue.claimNext().isEmpty());
  }

  @Test
  void exhaustedScheduledReminderIsNeverClaimed() {
    mongo.insert(
        reminder("already-exhausted", 5, "scheduled", Instant.now().minusSeconds(60)), "reminders");

    assertTrue(queue.claimNext().isEmpty());

    Document stored = mongo.findById("already-exhausted", Document.class, "reminders");

    assertNotNull(stored);
    assertEquals(5, stored.getInteger("attempts"));
  }

  @Test
  void staleWorkerCannotOverwriteReclaimedAttempt() {
    mongo.insert(
        reminder("stale-worker", 0, "scheduled", Instant.now().minusSeconds(60)), "reminders");

    Document firstClaim = queue.claimNext().orElseThrow();

    Document stored = mongo.findById("stale-worker", Document.class, "reminders");

    assertNotNull(stored);

    stored.put("lockedUntil", Date.from(Instant.now().minusSeconds(120)));

    mongo.save(stored, "reminders");

    assertEquals(1, queue.recoverExpiredClaims());

    Document secondClaim = queue.claimNext().orElseThrow();

    assertEquals(2, secondClaim.getInteger("attempts"));

    boolean oldWorkerUpdated =
        queue.finishAttempt(firstClaim, List.of(), Instant.now(), true, false);

    assertFalse(oldWorkerUpdated);

    Document finalState = mongo.findById("stale-worker", Document.class, "reminders");

    assertNotNull(finalState);
    assertEquals("sending", finalState.getString("status"));
    assertEquals(2, finalState.getInteger("attempts"));
  }

  @Test
  void failedAttemptsUseExpectedBackoff() {
    long[] expectedMinutes = {1, 5, 15, 60};

    for (int attempt = 1; attempt <= 4; attempt++) {
      String id = "backoff-" + attempt;

      mongo.insert(
          reminder(id, attempt - 1, "scheduled", Instant.now().minusSeconds(60)), "reminders");

      Document claimed = queue.claimNext().orElseThrow();

      assertEquals(attempt, claimed.getInteger("attempts"));

      Instant finishedAt = Instant.now();

      Document failedDelivery =
          new Document("attemptNo", attempt)
              .append("channel", "push")
              .append("provider", "fake")
              .append("outcome", "failed")
              .append("errorCode", "provider-timeout")
              .append("latencyMs", 10L)
              .append("attemptedAt", Date.from(finishedAt));

      assertTrue(queue.finishAttempt(claimed, List.of(failedDelivery), finishedAt, false, false));

      Document stored = mongo.findById(id, Document.class, "reminders");

      assertNotNull(stored);
      assertEquals("scheduled", stored.getString("status"));
      assertEquals(attempt, stored.getInteger("attempts"));
      assertNull(stored.get("lockedUntil"));

      assertEquals(
          Date.from(finishedAt.plusSeconds(expectedMinutes[attempt - 1] * 60)).toInstant(),
          stored.getDate("dueAt").toInstant());

      mongo.remove(stored, "reminders");
    }
  }

  @Test
  void providerFailsTwiceThenSucceedsExactlyOnce() {
    String id = "temporary-provider-failure";

    mongo.insert(reminder(id, 0, "scheduled", Instant.now().minusSeconds(60)), "reminders");

    java.util.concurrent.atomic.AtomicInteger sendCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    java.util.concurrent.atomic.AtomicInteger successCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    org.shp.notification.app.NotificationChannel fakeProvider =
        new org.shp.notification.app.NotificationChannel() {
          @Override
          public String channelName() {
            return "push";
          }

          @Override
          public org.shp.notification.app.NotificationResult send(
              org.shp.notification.app.NotificationMessage message) {

            int call = sendCalls.incrementAndGet();

            if (call <= 2) {
              return org.shp.notification.app.NotificationResult.failure("provider-timeout");
            }

            successCalls.incrementAndGet();

            return org.shp.notification.app.NotificationResult.success("provider-message-123");
          }
        };

    org.shp.notification.app.ReminderSender sender =
        new org.shp.notification.app.ReminderSender(
            queue,
            List.of(fakeProvider),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            java.time.Clock.systemUTC());

    for (int expectedAttempt = 1; expectedAttempt <= 3; expectedAttempt++) {
      Document claimed = queue.claimNext().orElseThrow();

      assertEquals(expectedAttempt, claimed.getInteger("attempts"));

      assertTrue(
          sender.sendClaimed(
              claimed,
              List.of("push"),
              channel ->
                  new org.shp.notification.app.NotificationMessage(
                      "fake-recipient", "Appointment reminder", "Your appointment is scheduled")));

      Document current = mongo.findById(id, Document.class, "reminders");

      assertNotNull(current);

      if (expectedAttempt < 3) {
        assertEquals("scheduled", current.getString("status"));

        // Simulate the retry becoming due, without waiting
        // one or five real minutes during the test.
        mongo.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
            new org.springframework.data.mongodb.core.query.Update()
                .set("dueAt", Date.from(Instant.now().minusSeconds(1))),
            "reminders");
      }
    }

    Document stored = mongo.findById(id, Document.class, "reminders");

    assertNotNull(stored);
    assertEquals("sent", stored.getString("status"));
    assertEquals(3, stored.getInteger("attempts"));
    assertNotNull(stored.getDate("completedAt"));

    List<Document> deliveries = stored.getList("deliveries", Document.class);

    assertEquals(3, deliveries.size());

    assertEquals(1, deliveries.get(0).getInteger("attemptNo"));
    assertEquals("failed", deliveries.get(0).getString("outcome"));

    assertEquals(2, deliveries.get(1).getInteger("attemptNo"));
    assertEquals("failed", deliveries.get(1).getString("outcome"));

    assertEquals(3, deliveries.get(2).getInteger("attemptNo"));
    assertEquals("accepted", deliveries.get(2).getString("outcome"));

    assertEquals(3, sendCalls.get());
    assertEquals(1, successCalls.get());

    assertTrue(queue.claimNext().isEmpty());

    // No sensitive contact or message content should be persisted.
    String persisted = stored.toJson();

    assertFalse(persisted.contains("fake-recipient"));
    assertFalse(persisted.contains("Your appointment is scheduled"));
  }

  @Test
  void workLoopDispatchesAndRetriesUntilSuccess() {
    String id = "workloop-retry";

    mongo.insert(reminder(id, 0, "scheduled", Instant.now().minusSeconds(60)), "reminders");

    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();

    org.shp.notification.app.NotificationChannel fakeProvider =
        new org.shp.notification.app.NotificationChannel() {
          @Override
          public String channelName() {
            return "push";
          }

          @Override
          public org.shp.notification.app.NotificationResult send(
              org.shp.notification.app.NotificationMessage message) {
            int current = calls.incrementAndGet();

            if (current <= 2) {
              return org.shp.notification.app.NotificationResult.failure("provider-timeout");
            }

            return org.shp.notification.app.NotificationResult.success("accepted-" + current);
          }
        };

    org.shp.notification.app.ReminderSender sender =
        new org.shp.notification.app.ReminderSender(
            queue,
            List.of(fakeProvider),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            java.time.Clock.systemUTC());

    org.shp.notification.app.ReminderWorkLoop loop =
        new org.shp.notification.app.ReminderWorkLoop(queue);

    for (int attempt = 1; attempt <= 3; attempt++) {
      int processed =
          loop.runBatch(claimed ->
              assertTrue(
                  sender.sendClaimed(
                      claimed,
                      List.of("push"),
                      channel ->
                          new org.shp.notification.app.NotificationMessage(
                              "fake-recipient", "Reminder", "Appointment reminder"))));

      assertEquals(1, processed);

      Document stored = mongo.findById(id, Document.class, "reminders");

      assertNotNull(stored);
      assertEquals(attempt, stored.getInteger("attempts"));

      if (attempt < 3) {
        assertEquals("scheduled", stored.getString("status"));

        // Advance the due time in the test instead of waiting.
        mongo.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
            new org.springframework.data.mongodb.core.query.Update()
                .set("dueAt", Date.from(Instant.now().minusSeconds(1))),
            "reminders");
      } else {
        assertEquals("sent", stored.getString("status"));
        assertNotNull(stored.getDate("completedAt"));
      }
    }

    Document finalReminder = mongo.findById(id, Document.class, "reminders");

    assertNotNull(finalReminder);
    assertEquals(3, finalReminder.getList("deliveries", Document.class).size());
    assertEquals(3, calls.get());

    // A completed reminder must not be dispatched again.
    assertEquals(0, loop.runBatch(claimed -> fail("Already sent reminder must not be dispatched")));

    assertEquals(3, calls.get());
  }

  @Test
  void deadLetterGaugeIncludesExpiredFifthAttempt() {
    io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    try {
      new ReminderDeadLetterMetrics(mongo, registry);

      io.micrometer.core.instrument.Gauge gauge =
          registry.find("shp.notification.deadletter.pending").gauge();

      assertNotNull(gauge);
      assertEquals(0.0, gauge.value());

      Document expired =
          reminder("gauge-expired-fifth", 5, "sending", Instant.now().minusSeconds(60));

      expired.put("lockedUntil", Date.from(Instant.now().minusSeconds(120)));

      mongo.insert(expired, "reminders");

      assertEquals(1, queue.recoverExpiredClaims());
      assertEquals(1.0, gauge.value());

      Document stored = mongo.findById("gauge-expired-fifth", Document.class, "reminders");

      assertNotNull(stored);
      assertEquals("dead-lettered", stored.getString("status"));

      // Repeated recovery must not create another dead-letter.
      assertEquals(0, queue.recoverExpiredClaims());
      assertEquals(1.0, gauge.value());

    } finally {
      registry.close();
    }
  }

  @Test
  void sameClaimCannotPersistDeliveryTwice() {
    String id = "duplicate-attempt";

    mongo.insert(reminder(id, 0, "scheduled", Instant.now().minusSeconds(60)), "reminders");

    Document claimed = queue.claimNext().orElseThrow();

    Document delivery =
        new Document("attemptNo", 1)
            .append("channel", "push")
            .append("provider", "fake")
            .append("outcome", "accepted")
            .append("providerMessageId", "message-1")
            .append("latencyMs", 10L)
            .append("attemptedAt", new Date());

    assertTrue(queue.finishAttempt(claimed, List.of(delivery), Instant.now(), true, false));

    assertFalse(queue.finishAttempt(claimed, List.of(delivery), Instant.now(), true, false));

    Document stored = mongo.findById(id, Document.class, "reminders");

    assertNotNull(stored);
    assertEquals("sent", stored.getString("status"));
    assertEquals(1, stored.getInteger("attempts"));
    assertEquals(1, stored.getList("deliveries", Document.class).size());

    assertTrue(queue.claimNext().isEmpty());
  }

  @Test
  void expiredLockCannotPersistSuccessfulDelivery() {
    String id = "expired-lock-finish";

    mongo.insert(reminder(id, 0, "scheduled", Instant.now().minusSeconds(60)), "reminders");

    Document claimed = queue.claimNext().orElseThrow();

    mongo.updateFirst(
        org.springframework.data.mongodb.core.query.Query.query(
            org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
        new org.springframework.data.mongodb.core.query.Update()
            .set("lockedUntil", Date.from(Instant.now().minusSeconds(60))),
        "reminders");

    Document expiredClaim = mongo.findById(id, Document.class, "reminders");

    assertNotNull(expiredClaim);

    assertFalse(queue.finishAttempt(expiredClaim, List.of(), Instant.now(), true, false));

    Document stored = mongo.findById(id, Document.class, "reminders");

    assertNotNull(stored);
    assertEquals("sending", stored.getString("status"));
    assertNull(stored.getDate("completedAt"));
  }
}
