package org.shp.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Consumer;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.data.mongo.MongoInbox;
import org.shp.data.mongo.MongoMessaging;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.Uuids;
import org.shp.notification.app.ReminderService;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

class ReminderConsumerIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-20T02:00:00Z");
  private static final String APPOINTMENT_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
  private static final String NEW_APPOINTMENT_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e05";
  private static final String PATIENT_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";

  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static final MongoDBContainer MONGO =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();

  private static final MongoTemplate DB;
  private static final Consumer<Message<byte[]>> CONSUMER;

  static {
    MONGO.start();

    var factory =
        new SimpleMongoClientDatabaseFactory(
            MongoClients.create(MONGO.getReplicaSetUrl("notification-ntf02")),
            "notification-ntf02");

    DB = new MongoTemplate(factory);

    TransactionTemplate tx = new TransactionTemplate(new MongoTransactionManager(factory));

    DB.createCollection(ReminderService.COLLECTION);
    DB.createCollection(ReminderService.APPOINTMENT_STATES);
    DB.createCollection(MongoMessaging.INBOX);

    DB.indexOps(ReminderService.COLLECTION)
        .createIndex(
            new Index()
                .on("subjectRef", Sort.Direction.ASC)
                .on("kind", Sort.Direction.ASC)
                .unique()
                .named("ux_reminder_once"));

    MongoMessaging.ensureIndexes(DB, MongoMessaging.Store.INBOX);

    ReminderService reminderService = new ReminderService(DB, tx, CLOCK);
    MongoInbox inbox = new MongoInbox(DB, tx, CLOCK);

    CONSUMER = new ReminderConsumerConfig().appointmentEvents(inbox, reminderService);
  }

  @BeforeEach
  void reset() {
    DB.getCollection(ReminderService.COLLECTION).deleteMany(new Document());
    DB.getCollection(ReminderService.APPOINTMENT_STATES).deleteMany(new Document());
    DB.getCollection(MongoMessaging.INBOX).deleteMany(new Document());
  }

  @Test
  void bookedEventThroughConsumerCreatesThreeReminders() {
    Instant start = NOW.plus(Duration.ofDays(3));

    CONSUMER.accept(message(booked(start)));

    assertThat(count()).isEqualTo(3);
    assertThat(kinds()).containsExactlyInAnyOrder("booking-confirmation", "t-24h", "t-2h");

    assertThat(dueAt("booking-confirmation")).isEqualTo(NOW);
    assertThat(dueAt("t-24h")).isEqualTo(start.minus(Duration.ofHours(24)));
    assertThat(dueAt("t-2h")).isEqualTo(start.minus(Duration.ofHours(2)));
  }

  @Test
  void duplicateBookedEventThroughConsumerCreatesNoDuplicates() {
    CloudEvent event = booked(NOW.plus(Duration.ofDays(3)));
    Message<byte[]> payload = message(event);

    CONSUMER.accept(payload);
    CONSUMER.accept(payload);

    assertThat(count()).isEqualTo(3);

    // Different event ID, same appointment:
    // unique (subjectRef, kind) must still prevent duplicates.
    CONSUMER.accept(message(booked(NOW.plus(Duration.ofDays(3)))));

    assertThat(count()).isEqualTo(3);
  }

  @Test
  void pastMilestonesAreSkippedByConsumer() {
    CONSUMER.accept(message(booked(NOW.plus(Duration.ofHours(3)))));

    assertThat(kinds()).containsExactlyInAnyOrder("booking-confirmation", "t-2h");
  }

  @Test
  void appointmentWithinTwoHoursGetsOnlyConfirmation() {
    CONSUMER.accept(message(booked(NOW.plus(Duration.ofHours(1)))));

    assertThat(kinds()).containsExactly("booking-confirmation");
  }

  // ---------- NTF-03: E2 appointment cancellation ----------

  @Test
  void cancelledEventCancelsOutstandingRemindersAndCreatesNotice() {
    Instant start = NOW.plus(Duration.ofDays(3));

    CONSUMER.accept(message(booked(start)));
    assertThat(count()).isEqualTo(3);

    CONSUMER.accept(message(cancelled(start)));

    assertThat(statusFor(APPOINTMENT_ID, "booking-confirmation")).isEqualTo("cancelled");
    assertThat(statusFor(APPOINTMENT_ID, "t-24h")).isEqualTo("cancelled");
    assertThat(statusFor(APPOINTMENT_ID, "t-2h")).isEqualTo("cancelled");
    assertThat(statusFor(APPOINTMENT_ID, "cancellation")).isEqualTo("scheduled");
    assertThat(count()).isEqualTo(4);
  }

  @Test
  void cancelledEventIsIdempotent() {
    Instant start = NOW.plus(Duration.ofDays(3));

    CONSUMER.accept(message(booked(start)));

    Message<byte[]> event = message(cancelled(start));
    CONSUMER.accept(event);
    CONSUMER.accept(event);
    CONSUMER.accept(message(cancelled(start)));

    assertThat(count()).isEqualTo(4);
    assertThat(statusFor(APPOINTMENT_ID, "cancellation")).isEqualTo("scheduled");
  }

  @Test
  void cancellationDoesNotModifyAlreadySentReminder() {
    Instant start = NOW.plus(Duration.ofDays(3));

    CONSUMER.accept(message(booked(start)));

    DB.getCollection(ReminderService.COLLECTION)
        .updateOne(
            new Document("subjectRef", "Appointment/" + APPOINTMENT_ID)
                .append("kind", "booking-confirmation"),
            new Document("$set", new Document("status", "sent")));

    CONSUMER.accept(message(cancelled(start)));

    assertThat(statusFor(APPOINTMENT_ID, "booking-confirmation")).isEqualTo("sent");
    assertThat(statusFor(APPOINTMENT_ID, "t-24h")).isEqualTo("cancelled");
    assertThat(statusFor(APPOINTMENT_ID, "cancellation")).isEqualTo("scheduled");
  }

  // ---------- NTF-03: E3 appointment rescheduling ----------

  @Test
  void rescheduledEventCancelsOldAndCreatesNewMilestones() {
    Instant oldStart = NOW.plus(Duration.ofDays(3));
    Instant newStart = NOW.plus(Duration.ofDays(4));

    CONSUMER.accept(message(booked(oldStart)));
    CONSUMER.accept(message(rescheduled(oldStart, newStart)));

    assertThat(statusFor(APPOINTMENT_ID, "booking-confirmation")).isEqualTo("cancelled");
    assertThat(statusFor(APPOINTMENT_ID, "t-24h")).isEqualTo("cancelled");
    assertThat(statusFor(APPOINTMENT_ID, "t-2h")).isEqualTo("cancelled");

    assertThat(statusFor(NEW_APPOINTMENT_ID, "booking-confirmation")).isEqualTo("scheduled");
    assertThat(statusFor(NEW_APPOINTMENT_ID, "t-24h")).isEqualTo("scheduled");
    assertThat(statusFor(NEW_APPOINTMENT_ID, "t-2h")).isEqualTo("scheduled");

    assertThat(count()).isEqualTo(6);
  }

  @Test
  void rescheduledEventCreatesOnlyOneImmediateConfirmation() {
    Instant oldStart = NOW.plus(Duration.ofDays(3));
    Instant newStart = NOW.plus(Duration.ofDays(4));

    CONSUMER.accept(message(booked(oldStart)));

    Message<byte[]> event = message(rescheduled(oldStart, newStart));
    CONSUMER.accept(event);
    CONSUMER.accept(event);
    CONSUMER.accept(message(rescheduled(oldStart, newStart)));

    long confirmations =
        DB.getCollection(ReminderService.COLLECTION)
            .countDocuments(
                new Document("subjectRef", "Appointment/" + NEW_APPOINTMENT_ID)
                    .append("kind", "booking-confirmation"));

    assertThat(confirmations).isEqualTo(1);
    assertThat(count()).isEqualTo(6);
    assertThat(statusFor(APPOINTMENT_ID, "cancellation")).isNull();
  }

  @Test
  void lateBookedEventDoesNotRecreateCancelledAppointmentReminders() {
    Instant start = NOW.plus(Duration.ofDays(3));

    // Cancellation is delivered before the original booking event.
    CONSUMER.accept(message(cancelled(start)));
    CONSUMER.accept(message(booked(start)));

    assertThat(count()).isEqualTo(1);
    assertThat(statusFor(APPOINTMENT_ID, "cancellation")).isEqualTo("scheduled");
    assertThat(statusFor(APPOINTMENT_ID, "booking-confirmation")).isNull();
    assertThat(statusFor(APPOINTMENT_ID, "t-24h")).isNull();
    assertThat(statusFor(APPOINTMENT_ID, "t-2h")).isNull();
  }

  @Test
  void cancellationInvalidatesAlreadyClaimedReminder() {
    Instant start = NOW.plus(Duration.ofDays(3));

    CONSUMER.accept(message(booked(start)));

    DB.getCollection(ReminderService.COLLECTION)
        .updateOne(
            new Document("subjectRef", "Appointment/" + APPOINTMENT_ID).append("kind", "t-24h"),
            new Document("$set", new Document("status", "sending")));

    CONSUMER.accept(message(cancelled(start)));

    assertThat(statusFor(APPOINTMENT_ID, "t-24h")).isEqualTo("cancelled");
    assertThat(statusFor(APPOINTMENT_ID, "cancellation")).isEqualTo("scheduled");
  }

  @Test
  void rescheduleBeforeBookingDoesNotRestoreOldReminders() {
    Instant oldStart = NOW.plus(Duration.ofDays(3));
    Instant newStart = NOW.plus(Duration.ofDays(4));

    CONSUMER.accept(message(rescheduled(oldStart, newStart)));

    // An older booking event arrives after rescheduling.
    CONSUMER.accept(message(booked(oldStart)));

    assertThat(statusFor(APPOINTMENT_ID, "booking-confirmation")).isNull();
    assertThat(statusFor(APPOINTMENT_ID, "t-24h")).isNull();
    assertThat(statusFor(APPOINTMENT_ID, "t-2h")).isNull();

    assertThat(statusFor(NEW_APPOINTMENT_ID, "booking-confirmation")).isEqualTo("scheduled");
    assertThat(statusFor(NEW_APPOINTMENT_ID, "t-24h")).isEqualTo("scheduled");
    assertThat(statusFor(NEW_APPOINTMENT_ID, "t-2h")).isEqualTo("scheduled");
  }

  @Test
  void rescheduleInvalidatesClaimedOldReminder() {
    Instant oldStart = NOW.plus(Duration.ofDays(3));
    Instant newStart = NOW.plus(Duration.ofDays(4));

    CONSUMER.accept(message(booked(oldStart)));

    DB.getCollection(ReminderService.COLLECTION)
        .updateOne(
            new Document("subjectRef", "Appointment/" + APPOINTMENT_ID).append("kind", "t-24h"),
            new Document("$set", new Document("status", "sending")));

    CONSUMER.accept(message(rescheduled(oldStart, newStart)));

    assertThat(statusFor(APPOINTMENT_ID, "t-24h")).isEqualTo("cancelled");
    assertThat(statusFor(NEW_APPOINTMENT_ID, "t-24h")).isEqualTo("scheduled");
  }

  @Test
  void reschedulePreservesAlreadySentReminder() {
    Instant oldStart = NOW.plus(Duration.ofDays(3));
    Instant newStart = NOW.plus(Duration.ofDays(4));

    CONSUMER.accept(message(booked(oldStart)));

    DB.getCollection(ReminderService.COLLECTION)
        .updateOne(
            new Document("subjectRef", "Appointment/" + APPOINTMENT_ID)
                .append("kind", "booking-confirmation"),
            new Document("$set", new Document("status", "sent")));

    CONSUMER.accept(message(rescheduled(oldStart, newStart)));

    assertThat(statusFor(APPOINTMENT_ID, "booking-confirmation")).isEqualTo("sent");
    assertThat(statusFor(APPOINTMENT_ID, "t-24h")).isEqualTo("cancelled");
    assertThat(statusFor(NEW_APPOINTMENT_ID, "booking-confirmation")).isEqualTo("scheduled");
  }

  @Test
  void lateNoShowScoreDoesNotRecreateCancelledReminder() {
    Instant start = NOW.plus(Duration.ofDays(3));

    CONSUMER.accept(message(booked(start)));
    CONSUMER.accept(message(cancelled(start)));
    CONSUMER.accept(message(noShowScored(start)));

    assertThat(statusFor(APPOINTMENT_ID, "t-6h-risk")).isNull();
  }

  @Test
  void lateNoShowScoreDoesNotRecreateRescheduledReminder() {
    Instant oldStart = NOW.plus(Duration.ofDays(3));
    Instant newStart = NOW.plus(Duration.ofDays(4));

    CONSUMER.accept(message(booked(oldStart)));
    CONSUMER.accept(message(rescheduled(oldStart, newStart)));
    CONSUMER.accept(message(noShowScored(oldStart)));

    assertThat(statusFor(APPOINTMENT_ID, "t-6h-risk")).isNull();
    assertThat(statusFor(NEW_APPOINTMENT_ID, "booking-confirmation")).isEqualTo("scheduled");
  }

  @Test
  void rescheduleConfirmationContainsPreviousAppointmentTime() {
    Instant oldStart = NOW.plus(Duration.ofDays(3));
    Instant newStart = NOW.plus(Duration.ofDays(4));

    CONSUMER.accept(message(booked(oldStart)));
    CONSUMER.accept(message(rescheduled(oldStart, newStart)));

    Document confirmation =
        DB.getCollection(ReminderService.COLLECTION)
            .find(
                new Document("subjectRef", "Appointment/" + NEW_APPOINTMENT_ID)
                    .append("kind", "booking-confirmation"))
            .first();

    assertThat(confirmation).isNotNull();
    assertThat(confirmation.getString("noticeType")).isEqualTo("reschedule");
    assertThat(confirmation.getDate("previousStart").toInstant()).isEqualTo(oldStart);
    assertThat(confirmation.getDate("appointmentStart").toInstant()).isEqualTo(newStart);

    long count =
        DB.getCollection(ReminderService.COLLECTION)
            .countDocuments(
                new Document("subjectRef", "Appointment/" + NEW_APPOINTMENT_ID)
                    .append("kind", "booking-confirmation"));

    assertThat(count).isEqualTo(1);
  }

  private static String statusFor(String appointmentId, String kind) {
    Document reminder =
        DB.getCollection(ReminderService.COLLECTION)
            .find(new Document("subjectRef", "Appointment/" + appointmentId).append("kind", kind))
            .first();

    return reminder == null ? null : reminder.getString("status");
  }

  private static long count() {
    return DB.getCollection(ReminderService.COLLECTION).countDocuments();
  }

  private static java.util.List<String> kinds() {
    return DB
        .getCollection(ReminderService.COLLECTION)
        .find()
        .into(new java.util.ArrayList<>())
        .stream()
        .map(doc -> doc.getString("kind"))
        .toList();
  }

  private static Instant dueAt(String kind) {
    Document document =
        DB.getCollection(ReminderService.COLLECTION).find(new Document("kind", kind)).first();

    assertThat(document).isNotNull();
    return document.getDate("dueAt").toInstant();
  }

  private static Message<byte[]> message(CloudEvent event) {
    return MessageBuilder.withPayload(CloudEvents.toJson(event).getBytes(StandardCharsets.UTF_8))
        .build();
  }

  private static CloudEvent cancelled(Instant start) {
    return CloudEvents.create(
        "scheduling",
        ReminderService.CANCELLED_TYPE,
        "Appointment/" + APPOINTMENT_ID,
        Map.of(
            "appointmentId",
            APPOINTMENT_ID,
            "version",
            2,
            "patientId",
            PATIENT_ID,
            "practitionerId",
            "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03",
            "start",
            start.toString(),
            "cancelReason",
            "patient-request",
            "cancelledBy",
            "patient",
            "cancelledAt",
            NOW.toString(),
            "hoursBeforeStart",
            72.0),
        CLOCK,
        Uuids.v7(CLOCK));
  }

  private static CloudEvent rescheduled(Instant oldStart, Instant newStart) {
    return CloudEvents.create(
        "scheduling",
        ReminderService.RESCHEDULED_TYPE,
        "Appointment/" + APPOINTMENT_ID,
        Map.of(
            "oldAppointmentId",
            APPOINTMENT_ID,
            "newAppointmentId",
            NEW_APPOINTMENT_ID,
            "version",
            2,
            "patientId",
            PATIENT_ID,
            "practitionerId",
            "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03",
            "oldStart",
            oldStart.toString(),
            "newStart",
            newStart.toString(),
            "newEnd",
            newStart.plus(Duration.ofMinutes(15)).toString(),
            "specialty",
            "cardiology"),
        CLOCK,
        Uuids.v7(CLOCK));
  }

  private static CloudEvent noShowScored(Instant start) {
    return CloudEvents.create(
        "ml",
        ReminderService.NO_SHOW_SCORED_TYPE,
        "Appointment/" + APPOINTMENT_ID,
        Map.of(
            "appointmentId",
            APPOINTMENT_ID,
            "patientId",
            PATIENT_ID,
            "start",
            start.toString(),
            "score",
            0.42,
            "threshold",
            0.35,
            "highRisk",
            true,
            "modelName",
            "no-show",
            "modelVersion",
            "3",
            "scoredAt",
            NOW.toString()),
        CLOCK,
        Uuids.v7(CLOCK));
  }

  private static CloudEvent booked(Instant start) {
    return CloudEvents.create(
        "scheduling",
        ReminderService.BOOKED_TYPE,
        "Appointment/" + APPOINTMENT_ID,
        Map.of(
            "appointmentId",
            APPOINTMENT_ID,
            "version",
            1,
            "patientId",
            PATIENT_ID,
            "practitionerId",
            "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03",
            "slotId",
            "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e04",
            "specialty",
            "cardiology",
            "start",
            start.toString(),
            "end",
            start.plus(Duration.ofMinutes(15)).toString(),
            "channel",
            "web",
            "bookedAt",
            "2026-11-20T02:15:00Z"),
        CLOCK,
        Uuids.v7(CLOCK));
  }
}
