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
    DB.getCollection(MongoMessaging.INBOX).deleteMany(new Document());
    DB.getCollection(ReminderService.PREFERENCES).deleteMany(new Document());
    DB.getCollection(ReminderService.DEVICE_TOKENS).deleteMany(new Document());
  }

  @Test
  void highRiskScoreWithTimeLeftCreatesT6hRiskReminderOnce() {
    Instant start = NOW.plus(Duration.ofDays(1));
    CloudEvent event = scored(start, true);

    CONSUMER.accept(message(event));
    CONSUMER.accept(message(event));

    assertThat(kinds()).containsExactly("t-6h-risk");
    assertThat(dueAt("t-6h-risk")).isEqualTo(start.minus(Duration.ofHours(6)));
  }

  @Test
  void lowRiskOrNoTimeLeftCreatesNoT6hRiskReminder() {
    CONSUMER.accept(message(scored(NOW.plus(Duration.ofDays(1)), false)));
    CONSUMER.accept(message(scored(NOW.plus(Duration.ofHours(5)), true)));

    assertThat(count()).isZero();
  }

  @Test
  void documentReleasedCreatesOneGenericResultAvailableReminder() {
    CloudEvent event = released("0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e08");

    CONSUMER.accept(message(event));
    CONSUMER.accept(message(event));

    Document reminder = DB.getCollection(ReminderService.COLLECTION).find().first();
    assertThat(count()).isEqualTo(1);
    assertThat(reminder).isNotNull();
    assertThat(reminder.getString("kind")).isEqualTo("result-available");
    assertThat(reminder.getString("patientId")).isEqualTo(PATIENT_ID);
    assertThat(reminder.getDate("dueAt").toInstant()).isEqualTo(NOW);
    assertThat(reminder.get("appointmentStart")).isNull();
    // Generic content: neither the document type nor any result detail is stored.
    assertThat(reminder.toJson()).doesNotContain("11502-2");
  }

  @Test
  void patientMergedMovesRemindersTokensAndPreferencesToTheTarget() {
    String target = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e07";
    CONSUMER.accept(message(booked(NOW.plus(Duration.ofDays(3)))));
    DB.getCollection(ReminderService.DEVICE_TOKENS)
        .insertOne(new Document("_id", "d1").append("ownerRef", "Patient/" + PATIENT_ID));
    DB.getCollection(ReminderService.PREFERENCES)
        .insertOne(new Document("_id", PATIENT_ID).append("locale", "en"));

    CloudEvent event = merged(PATIENT_ID, target);
    CONSUMER.accept(message(event));
    CONSUMER.accept(message(event));

    assertThat(
            DB.getCollection(ReminderService.COLLECTION)
                .countDocuments(new Document("patientId", target)))
        .isEqualTo(3);
    assertThat(
            DB.getCollection(ReminderService.COLLECTION)
                .countDocuments(new Document("patientId", PATIENT_ID)))
        .isZero();
    assertThat(DB.getCollection(ReminderService.DEVICE_TOKENS).find().first().getString("ownerRef"))
        .isEqualTo("Patient/" + target);
    assertThat(DB.getCollection(ReminderService.PREFERENCES).find().first().getString("_id"))
        .isEqualTo(target);
  }

  @Test
  void patientMergedKeepsTheTargetPreferencesWhenItHasSome() {
    String target = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e07";
    DB.getCollection(ReminderService.PREFERENCES)
        .insertOne(new Document("_id", PATIENT_ID).append("locale", "en"));
    DB.getCollection(ReminderService.PREFERENCES)
        .insertOne(new Document("_id", target).append("locale", "vi"));

    CONSUMER.accept(message(merged(PATIENT_ID, target)));

    assertThat(DB.getCollection(ReminderService.PREFERENCES).countDocuments()).isEqualTo(1);
    assertThat(DB.getCollection(ReminderService.PREFERENCES).find().first().getString("locale"))
        .isEqualTo("vi");
  }

  private static CloudEvent scored(Instant start, boolean highRisk) {
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
            0.8,
            "threshold",
            0.5,
            "highRisk",
            highRisk,
            "modelName",
            "no-show",
            "modelVersion",
            "1",
            "scoredAt",
            NOW.toString()),
        CLOCK,
        Uuids.v7(CLOCK));
  }

  private static CloudEvent released(String documentId) {
    return CloudEvents.create(
        "document",
        ReminderService.DOCUMENT_RELEASED_TYPE,
        "Document/" + documentId,
        Map.of(
            "documentId",
            documentId,
            "patientId",
            PATIENT_ID,
            "releasedAt",
            NOW.toString(),
            "type",
            "11502-2"),
        CLOCK,
        Uuids.v7(CLOCK));
  }

  private static CloudEvent merged(String source, String target) {
    return CloudEvents.create(
        "clinical",
        ReminderService.PATIENT_MERGED_TYPE,
        "Patient/" + target,
        Map.of("sourcePatientId", source, "targetPatientId", target, "mergedAt", NOW.toString()),
        CLOCK,
        Uuids.v7(CLOCK));
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
