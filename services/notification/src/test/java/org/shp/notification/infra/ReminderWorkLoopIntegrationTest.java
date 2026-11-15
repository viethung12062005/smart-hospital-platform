package org.shp.notification.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.notification.app.ReminderWorkLoop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
class ReminderWorkLoopIntegrationTest {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO =
      new MongoDBContainer(
              DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8")))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();

  @Autowired MongoTemplate mongoTemplate;

  @Autowired MongoReminderWorkQueue workQueue;

  @Autowired ReminderWorkLoop workLoop;

  @BeforeEach
  void cleanCollection() {
    mongoTemplate.dropCollection("reminders");
  }

  @Test
  void twoWorkersMustNotClaimTheSameReminder() throws Exception {
    insertReminder("scheduled", Instant.now().minusSeconds(30), null);

    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Optional<Document>> first =
          workers.submit(() -> {
            start.await();
            return workQueue.claimNext();
          });

      Future<Optional<Document>> second =
          workers.submit(() -> {
            start.await();
            return workQueue.claimNext();
          });

      start.countDown();

      Optional<Document> firstClaim = first.get();
      Optional<Document> secondClaim = second.get();

      assertNotEquals(
          firstClaim.isPresent(),
          secondClaim.isPresent(),
          "Exactly one worker must claim the reminder");

      Document claimed = firstClaim.orElseGet(secondClaim::orElseThrow);
      assertEquals("sending", claimed.getString("status"));
      assertEquals(1, claimed.getInteger("attempts"));
      assertNotNull(claimed.getDate("lockedUntil"));
    }
  }

  @Test
  void expiredSendingReminderMustBeRecoveredAndClaimedAgain() {
    String id =
        insertReminder("sending", Instant.now().minusSeconds(60), Instant.now().minusSeconds(10));

    long recovered = workLoop.recoverStuckReminders();

    assertEquals(1, recovered);

    Document recoveredReminder = mongoTemplate.findById(id, Document.class, "reminders");

    assertNotNull(recoveredReminder);
    assertEquals("scheduled", recoveredReminder.getString("status"));
    assertNull(recoveredReminder.get("lockedUntil"));

    Optional<Document> reclaimed = workQueue.claimNext();

    assertTrue(reclaimed.isPresent());
    assertEquals(id, reclaimed.get().getString("_id"));
    assertEquals("sending", reclaimed.get().getString("status"));
    assertEquals(2, reclaimed.get().getInteger("attempts"));
  }

  @Test
  void futureReminderMustNotBeClaimed() {
    insertReminder("scheduled", Instant.now().plusSeconds(3600), null);

    assertTrue(workQueue.claimNext().isEmpty());
  }

  @Test
  void recoveryMustNotReleaseAnUnexpiredLock() {
    insertReminder("sending", Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));

    assertEquals(0, workLoop.recoverStuckReminders());
    assertTrue(workQueue.claimNext().isEmpty());
  }

  private String insertReminder(String status, Instant dueAt, Instant lockedUntil) {
    Instant now = Instant.now();
    String id = UUID.randomUUID().toString();

    Document reminder =
        new Document("_id", id)
            .append("subjectRef", "Appointment/" + UUID.randomUUID())
            .append("patientId", UUID.randomUUID().toString())
            .append("kind", "t-24h")
            .append("appointmentStart", Date.from(now.plusSeconds(86400)))
            .append("dueAt", Date.from(dueAt))
            .append("status", status)
            .append("attempts", status.equals("sending") ? 1 : 0)
            .append("lockedUntil", lockedUntil == null ? null : Date.from(lockedUntil))
            .append("deliveries", java.util.List.of())
            .append("createdAt", Date.from(now))
            .append("updatedAt", Date.from(now));

    mongoTemplate.insert(reminder, "reminders");
    return id;
  }
}
