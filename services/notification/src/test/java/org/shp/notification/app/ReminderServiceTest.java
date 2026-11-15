package org.shp.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.domain.Sort.Direction.ASC;

import com.mongodb.client.MongoClients;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.data.mongo.MongoInbox;
import org.shp.data.mongo.MongoMessaging;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.Uuids;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Reminder creation against a real MongoDB replica set (Testcontainers). Covers the E1 milestones,
 * duplicate delivery, past milestones, the E10 high-risk reminder and ML-12 AC-15.1: a missing
 * no-show score does not stop the t-24h and t-2h reminders.
 */
class ReminderServiceTest {
  private static final String APPOINTMENT_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
  private static final String PATIENT_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
  private static final Instant NOW = Instant.parse("2026-11-20T02:00:00Z");
  private static final Instant START = Instant.parse("2026-12-03T01:15:00Z");

  static final MongoDBContainer MONGO =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();
  static final MongoTemplate DB;
  static final TransactionTemplate TX;
  static final MutableClock CLOCK = new MutableClock(NOW);

  static {
    MONGO.start();
    var factory =
        new SimpleMongoClientDatabaseFactory(
            MongoClients.create(MONGO.getReplicaSetUrl("notification")), "notification");
    DB = new MongoTemplate(factory);
    TX = new TransactionTemplate(new MongoTransactionManager(factory));
    for (String collection : new String[] {ReminderService.COLLECTION, MongoMessaging.INBOX}) {
      if (!DB.collectionExists(collection)) {
        DB.createCollection(collection);
      }
    }
    DB.indexOps(ReminderService.COLLECTION)
        .createIndex(
            new Index().on("subjectRef", ASC).on("kind", ASC).unique().named("ux_reminder_once"));
    MongoMessaging.ensureIndexes(DB, MongoMessaging.Store.INBOX);
  }

  private final ReminderService reminders = new ReminderService(DB, TX, CLOCK);
  private final MongoInbox inbox = new MongoInbox(DB, TX, CLOCK);

  @BeforeEach
  void reset() {
    CLOCK.now = NOW;
    DB.getCollection(ReminderService.COLLECTION).deleteMany(new Document());
    DB.getCollection(MongoMessaging.INBOX).deleteMany(new Document());
  }

  @Test
  void bookedCreatesConfirmationAndFutureMilestones() {
    reminders.onBooked(booked(START));

    Map<String, Document> saved = remindersByKind();
    assertThat(saved).containsOnlyKeys("booking-confirmation", "t-24h", "t-2h");
    assertThat(dueAt(saved.get("booking-confirmation"))).isEqualTo(NOW);
    assertThat(dueAt(saved.get("t-24h"))).isEqualTo(START.minus(Duration.ofHours(24)));
    assertThat(dueAt(saved.get("t-2h"))).isEqualTo(START.minus(Duration.ofHours(2)));
    assertThat(saved.get("t-24h").getString("patientId")).isEqualTo(PATIENT_ID);
    assertThat(saved.get("t-24h").getString("subjectRef"))
        .isEqualTo("Appointment/" + APPOINTMENT_ID);
    assertThat(saved.get("t-24h").getString("status")).isEqualTo("scheduled");
    assertThat(saved.get("t-24h").getInteger("attempts")).isZero();
    assertThat(saved.get("t-24h").getList("deliveries", Document.class)).isEmpty();
  }

  @Test
  void replayedBookedCreatesNoDuplicates() {
    CloudEvent event = booked(START);

    assertThat(inbox.handleOnce("notification", event, () -> reminders.onBooked(event))).isTrue();
    assertThat(inbox.handleOnce("notification", event, () -> reminders.onBooked(event))).isFalse();
    reminders.onBooked(event); // a direct duplicate must also be a no-op

    assertThat(count()).isEqualTo(3);
  }

  @Test
  void pastMilestoneIsSkipped() {
    // Start three hours from now: t-24h is already past, t-2h is one hour ahead.
    reminders.onBooked(booked(NOW.plus(Duration.ofHours(3))));

    assertThat(remindersByKind()).containsOnlyKeys("booking-confirmation", "t-2h");
  }

  @Test
  void appointmentLessThanTwoHoursAwayOnlyGetsConfirmation() {
    reminders.onBooked(booked(NOW.plus(Duration.ofHours(1))));

    assertThat(remindersByKind()).containsOnlyKeys("booking-confirmation");
  }

  @Test
  void highRiskNoShowScoreAddsT6hReminder() {
    reminders.onBooked(booked(START));
    reminders.onNoShowScored(noShowScored(START, true));

    Map<String, Document> saved = remindersByKind();
    assertThat(saved).containsOnlyKeys("booking-confirmation", "t-24h", "t-2h", "t-6h-risk");
    assertThat(dueAt(saved.get("t-6h-risk"))).isEqualTo(START.minus(Duration.ofHours(6)));
  }

  @Test
  void noShowScoreWithoutHighRiskAddsNoReminder() {
    reminders.onBooked(booked(START));
    reminders.onNoShowScored(noShowScored(START, false));

    assertThat(remindersByKind()).containsOnlyKeys("booking-confirmation", "t-24h", "t-2h");
  }

  @Test
  void highRiskNoShowScoreTooLateIsSkipped() {
    Instant start = NOW.plus(Duration.ofHours(3));

    reminders.onNoShowScored(noShowScored(start, true));

    assertThat(count()).isZero();
  }

  /** ML-12 AC-15.1: the ML service never scores the appointment, reminders still exist. */
  @Test
  void withoutNoShowScoreT24hAndT2hAreStillCreated() {
    reminders.onBooked(booked(START));
    // No shp.ml.no-show-scored.v1 event: ML is down.

    assertThat(remindersByKind()).containsKeys("t-24h", "t-2h").doesNotContainKey("t-6h-risk");
  }

  private static long count() {
    return DB.getCollection(ReminderService.COLLECTION).countDocuments();
  }

  private static Map<String, Document> remindersByKind() {
    return DB
        .find(
            Query.query(Criteria.where("subjectRef").is("Appointment/" + APPOINTMENT_ID)),
            Document.class,
            ReminderService.COLLECTION)
        .stream()
        .collect(Collectors.toMap(document -> document.getString("kind"), document -> document));
  }

  private static Instant dueAt(Document document) {
    return document.getDate("dueAt").toInstant();
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

  private static CloudEvent noShowScored(Instant start, boolean highRisk) {
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
            highRisk ? 0.9 : 0.1,
            "threshold",
            0.5,
            "highRisk",
            highRisk,
            "modelName",
            "no-show",
            "modelVersion",
            "3",
            "scoredAt",
            "2026-11-20T02:15:00Z"),
        CLOCK,
        Uuids.v7(CLOCK));
  }

  static final class MutableClock extends Clock {
    volatile Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
