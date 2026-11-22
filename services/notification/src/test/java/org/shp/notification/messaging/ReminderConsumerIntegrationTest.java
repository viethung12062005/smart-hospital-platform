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
