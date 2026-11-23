package org.shp.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.notification.infra.MongoReminderManagementStore;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
class ReminderManagementServiceTest {

  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();

  private static final String APPOINTMENT_A = "appointment-a";
  private static final String APPOINTMENT_B = "appointment-b";

  @Autowired MongoTemplate mongo;

  private ReminderManagementService service;
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void setUp() {
    mongo.dropCollection("reminders");
    service = new ReminderManagementService(new MongoReminderManagementStore(mongo));
  }

  @Test
  void n5ReturnsOnlyRemindersForRequestedAppointment() {
    insert("reminder-1", APPOINTMENT_A, "scheduled", 0, "2026-12-01T08:00:00Z");
    insert("reminder-2", APPOINTMENT_A, "sent", 1, "2026-12-02T08:00:00Z");
    insert("reminder-3", APPOINTMENT_B, "scheduled", 0, "2026-12-03T08:00:00Z");

    var reminders = service.remindersForAppointment(APPOINTMENT_A);

    assertThat(reminders).hasSize(2);
    assertThat(reminders)
        .extracting(ReminderManagementService.ReminderView::reminderId)
        .containsExactly("reminder-1", "reminder-2");
  }

  @Test
  void n5ReturnsEmptyListWhenAppointmentHasNoReminders() {
    assertThat(service.remindersForAppointment("unknown")).isEmpty();
  }

  @Test
  void n6ReturnsOnlyDeadLetteredReminders() {
    insert("dead-1", APPOINTMENT_A, "dead-lettered", 5, "2026-12-01T08:00:00Z");
    insert("sent-1", APPOINTMENT_A, "sent", 1, "2026-12-02T08:00:00Z");

    var deadLetters = service.deadLetters();

    assertThat(deadLetters).hasSize(1);
    assertThat(deadLetters.getFirst().reminderId()).isEqualTo("dead-1");
    assertThat(deadLetters.getFirst().attempts()).isEqualTo(5);
  }

  @Test
  void n6ReturnsTheLatestDeliveryError() {
    insert("dead-1", APPOINTMENT_A, "dead-lettered", 5, "2026-12-01T08:00:00Z");

    var deadLetter = service.deadLetters().getFirst();

    assertThat(deadLetter.lastError()).isEqualTo("provider-timeout");
    assertThat(deadLetter.deadLetteredAt()).isNotNull();
  }

  @Test
  void n5AndN6ResponsesDoNotExposeContactOrDeliveryDetails() {
    insert("dead-1", APPOINTMENT_A, "dead-lettered", 5, "2026-12-01T08:00:00Z");

    String remindersJson = json.writeValueAsString(service.remindersForAppointment(APPOINTMENT_A));
    String deadLettersJson = json.writeValueAsString(service.deadLetters());

    for (String response : List.of(remindersJson, deadLettersJson)) {
      assertThat(response)
          .doesNotContain(
              "patient@example.test",
              "+84900123456",
              "secret-device-token",
              "providerMessageId",
              "patientId",
              "deliveries",
              "email",
              "phone");
    }
  }

  @Test
  void n6LimitsResultsToOneHundred() {
    for (int i = 0; i < 105; i++) {
      insert("dead-" + i, APPOINTMENT_A, "dead-lettered", 5, "2026-12-01T08:00:00Z");
    }

    assertThat(service.deadLetters()).hasSize(100);
  }

  // ---------- N7: atomic dead-letter retry ----------

  @Test
  void n7RetriesDeadLetterBeforeAppointmentStart() {
    insert(
        "retry-1",
        APPOINTMENT_A,
        "dead-lettered",
        5,
        Instant.now().plus(Duration.ofDays(2)).toString());

    Instant now = Instant.now();

    boolean retried = new MongoReminderManagementStore(mongo).retryDeadLetter("retry-1", now);

    assertThat(retried).isTrue();

    Document stored = mongo.findById("retry-1", Document.class, "reminders");

    assertThat(stored).isNotNull();
    assertThat(stored.getString("status")).isEqualTo("scheduled");
    assertThat(stored.getInteger("attempts")).isZero();
    assertThat(stored.getDate("dueAt")).isEqualTo(Date.from(now));
    assertThat(stored.getDate("updatedAt")).isEqualTo(Date.from(now));
    assertThat(stored.get("lockedUntil")).isNull();
    assertThat(stored.get("completedAt")).isNull();
  }

  @Test
  void n7RejectsRetryAfterAppointmentStart() {
    insert(
        "retry-past",
        APPOINTMENT_A,
        "dead-lettered",
        5,
        Instant.now().minus(Duration.ofDays(2)).toString());

    Document before = mongo.findById("retry-past", Document.class, "reminders");

    boolean retried =
        new MongoReminderManagementStore(mongo).retryDeadLetter("retry-past", Instant.now());

    Document after = mongo.findById("retry-past", Document.class, "reminders");

    assertThat(retried).isFalse();
    assertThat(after.getString("status")).isEqualTo("dead-lettered");
    assertThat(after.getInteger("attempts")).isEqualTo(5);
    assertThat(after).isEqualTo(before);
  }

  @Test
  void n7RejectsReminderThatIsNotDeadLettered() {
    insert(
        "retry-sent", APPOINTMENT_A, "sent", 1, Instant.now().plus(Duration.ofDays(2)).toString());

    boolean retried =
        new MongoReminderManagementStore(mongo).retryDeadLetter("retry-sent", Instant.now());

    assertThat(retried).isFalse();

    Document stored = mongo.findById("retry-sent", Document.class, "reminders");

    assertThat(stored.getString("status")).isEqualTo("sent");
    assertThat(stored.getInteger("attempts")).isEqualTo(1);
  }

  @Test
  void n7ConcurrentRetryOnlyOneRequestSucceeds() throws Exception {
    insert(
        "retry-concurrent",
        APPOINTMENT_A,
        "dead-lettered",
        5,
        Instant.now().plus(Duration.ofDays(2)).toString());

    var store = new MongoReminderManagementStore(mongo);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var task =
          (java.util.concurrent.Callable<Boolean>)
              () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Concurrent test timed out");
                }
                return store.retryDeadLetter("retry-concurrent", Instant.now());
              };

      var first = executor.submit(task);
      var second = executor.submit(task);

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    }

    Document stored = mongo.findById("retry-concurrent", Document.class, "reminders");

    assertThat(stored.getString("status")).isEqualTo("scheduled");
    assertThat(stored.getInteger("attempts")).isZero();
  }

  @Test
  void n7PreservesDeliveryHistoryAfterRetry() {
    insert(
        "retry-history",
        APPOINTMENT_A,
        "dead-lettered",
        5,
        Instant.now().plus(Duration.ofDays(2)).toString());

    Document before = mongo.findById("retry-history", Document.class, "reminders");

    List<Document> originalDeliveries = before.getList("deliveries", Document.class);

    boolean retried =
        new MongoReminderManagementStore(mongo).retryDeadLetter("retry-history", Instant.now());

    Document after = mongo.findById("retry-history", Document.class, "reminders");

    assertThat(retried).isTrue();
    assertThat(after.getList("deliveries", Document.class))
        .containsExactlyElementsOf(originalDeliveries);
  }

  // ---------- N7 service error handling ----------

  @Test
  void n7ServiceRejectsBlankReminderIdWith404() {
    assertThatThrownBy(() ->
            service.retryDeadLetter(
                " ", Clock.fixed(Instant.parse("2026-11-22T08:00:00Z"), ZoneOffset.UTC)))
        .isInstanceOf(ShpException.class)
        .satisfies(error -> assertThat(((ShpException) error).info().status()).isEqualTo(404));
  }

  @Test
  void n7ServiceRejectsPastAppointmentWith409() {
    insert("retry-service-past", APPOINTMENT_A, "dead-lettered", 5, "2026-11-01T08:00:00Z");

    assertThatThrownBy(() ->
            service.retryDeadLetter(
                "retry-service-past",
                Clock.fixed(Instant.parse("2026-11-22T08:00:00Z"), ZoneOffset.UTC)))
        .isInstanceOf(ShpException.class)
        .satisfies(error -> assertThat(((ShpException) error).info().status()).isEqualTo(409));
  }

  @Test
  void n7ServiceSuccessfullyRequeuesValidReminder() {
    insert("retry-service-valid", APPOINTMENT_A, "dead-lettered", 5, "2026-12-01T08:00:00Z");

    service.retryDeadLetter(
        "retry-service-valid", Clock.fixed(Instant.parse("2026-11-22T08:00:00Z"), ZoneOffset.UTC));

    Document stored = mongo.findById("retry-service-valid", Document.class, "reminders");

    assertThat(stored.getString("status")).isEqualTo("scheduled");
    assertThat(stored.getInteger("attempts")).isZero();
  }

  private void insert(String id, String appointment, String status, int attempts, String dueAt) {

    Instant due = Instant.parse(dueAt);

    var deliveries =
        List.of(
            new Document("attemptNo", 1)
                .append("channel", "push")
                .append("provider", "fake")
                .append("outcome", "failed")
                .append("errorCode", "provider-unavailable")
                .append("providerMessageId", "secret-device-token"),
            new Document("attemptNo", 2)
                .append("channel", "email")
                .append("outcome", "failed")
                .append("errorCode", "provider-timeout")
                .append("providerMessageId", "patient@example.test"));

    var reminder =
        new Document("_id", id)
            .append("subjectRef", "Appointment/" + appointment)
            .append("patientId", "patient-123")
            .append("kind", "t-24h")
            .append("appointmentStart", Date.from(due.plusSeconds(86400)))
            .append("dueAt", Date.from(due))
            .append("status", status)
            .append("attempts", attempts)
            .append("lockedUntil", null)
            .append("deliveries", deliveries)
            .append("createdAt", Date.from(due.minusSeconds(3600)))
            .append("updatedAt", Date.from(due))
            .append("completedAt", "dead-lettered".equals(status) ? Date.from(due) : null);

    mongo.insert(reminder, "reminders");
  }
}
