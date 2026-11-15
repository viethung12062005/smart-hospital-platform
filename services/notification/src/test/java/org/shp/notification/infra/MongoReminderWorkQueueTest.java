package org.shp.notification.infra;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MongoReminderWorkQueueTest {

  @Container
  static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:8"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();

  static com.mongodb.client.MongoClient client;
  static MongoTemplate mongo;
  static MongoReminderWorkQueue queue;

  @BeforeEach
  void setUp() {
    if (client == null) {
      client = MongoClients.create(MONGO.getReplicaSetUrl());
      mongo = new MongoTemplate(client, "notification_test");
      queue = new MongoReminderWorkQueue(mongo);
    }
    mongo.dropCollection("reminders");
  }

  @AfterAll
  static void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  private void insertReminder(String id, Instant dueAt, String status, Instant lockedUntil) {
    Document reminder =
        new Document("_id", id)
            .append("subjectRef", "Appointment/00000000-0000-0000-0000-000000000001")
            .append("patientId", "patient-1")
            .append("kind", "t-24h")
            .append("dueAt", Date.from(dueAt))
            .append("status", status)
            .append("attempts", 0)
            .append("createdAt", new Date())
            .append("updatedAt", new Date());

    if (lockedUntil != null) {
      reminder.append("lockedUntil", Date.from(lockedUntil));
    }

    mongo.insert(reminder, "reminders");
  }

  @Test
  void concurrentWorkersMustNotClaimSameReminder() throws Exception {
    insertReminder("reminder-1", Instant.now().minusSeconds(60), "scheduled", null);

    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(2);
    Set<String> claimedIds = ConcurrentHashMap.newKeySet();

    for (int i = 0; i < 2; i++) {
      executor.submit(() -> {
        try {
          start.await();
          queue.claimNext().ifPresent(doc -> claimedIds.add(doc.getString("_id")));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
    }

    start.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    executor.shutdownNow();

    assertEquals(1, claimedIds.size());
    assertEquals(
        "sending", mongo.findById("reminder-1", Document.class, "reminders").getString("status"));
    assertEquals(
        1, mongo.findById("reminder-1", Document.class, "reminders").getInteger("attempts"));
  }

  @Test
  void expiredClaimMustBeRecoveredAndClaimableAgain() {
    Instant now = Instant.now();

    insertReminder("stuck-reminder", now.minusSeconds(120), "sending", now.minusSeconds(30));

    assertEquals(1, queue.recoverExpiredClaims());

    Document recovered = mongo.findById("stuck-reminder", Document.class, "reminders");

    assertEquals("scheduled", recovered.getString("status"));
    assertNull(recovered.get("lockedUntil"));

    var claimed = queue.claimNext();

    assertTrue(claimed.isPresent());
    assertEquals("stuck-reminder", claimed.get().getString("_id"));
    assertEquals("sending", claimed.get().getString("status"));
    assertEquals(1, claimed.get().getInteger("attempts"));
  }

  @Test
  void futureReminderMustNotBeClaimed() {
    insertReminder("future-reminder", Instant.now().plusSeconds(3600), "scheduled", null);

    assertTrue(queue.claimNext().isEmpty());
  }

  @Test
  void unexpiredSendingReminderMustNotBeRecovered() {
    Instant now = Instant.now();

    insertReminder("active-reminder", now.minusSeconds(120), "sending", now.plusSeconds(60));

    assertEquals(0, queue.recoverExpiredClaims());

    Document reminder = mongo.findById("active-reminder", Document.class, "reminders");

    assertEquals("sending", reminder.getString("status"));
  }
}
