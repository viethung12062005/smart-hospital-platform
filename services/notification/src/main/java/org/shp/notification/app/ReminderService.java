package org.shp.notification.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.shp.data.mongo.MongoMessaging;
import org.shp.events.CloudEvent;
import org.shp.events.Uuids;
import org.shp.notification.domain.Reminder;
import org.shp.notification.domain.ReminderKind;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Reminder use cases (04-db-notification-ml §1.3). The event consumer already runs inside {@code
 * MongoInbox.handleOnce}; this class joins that transaction and is also safe to call directly
 * because the unique {@code (subjectRef, kind)} index makes the writes idempotent.
 */
public final class ReminderService {
  /** The {@code reminders} collection (04-db-notification-ml §1.2). */
  public static final String COLLECTION = "reminders";

  public static final String CONSUMER = "notification";
  public static final String BOOKED_TYPE = "shp.scheduling.appointment-booked.v1";
  public static final String NO_SHOW_SCORED_TYPE = "shp.ml.no-show-scored.v1";

  private static final Duration T_24H = Duration.ofHours(24);
  private static final Duration T_6H = Duration.ofHours(6);
  private static final Duration T_2H = Duration.ofHours(2);

  private final MongoTemplate mongo;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ReminderService(MongoTemplate mongo, TransactionTemplate tx, Clock clock) {
    this.mongo = mongo;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * E1 {@code appointment-booked}: a booking confirmation immediately, then the t-24h and t-2h
   * reminders when their milestone is still ahead. A past milestone is skipped (§1.3).
   */
  public void onBooked(CloudEvent event) {
    JsonNode data = event.data();
    String subjectRef = subjectRef(text(data, "appointmentId"));
    String patientId = text(data, "patientId");
    Instant appointmentStart = instant(data, "start");
    Instant now = clock.instant();

    List<Reminder> reminders = new ArrayList<>();
    reminders.add(
        reminder(
            subjectRef, patientId, ReminderKind.BOOKING_CONFIRMATION, appointmentStart, now, now));
    addIfFuture(
        reminders,
        subjectRef,
        patientId,
        ReminderKind.T_24H,
        appointmentStart,
        appointmentStart.minus(T_24H),
        now);
    addIfFuture(
        reminders,
        subjectRef,
        patientId,
        ReminderKind.T_2H,
        appointmentStart,
        appointmentStart.minus(T_2H),
        now);
    saveNew(reminders);
  }

  /**
   * E10 {@code no-show-scored}: a t-6h-risk reminder only when {@code highRisk} and {@code start -
   * 6h} is still in the future. Without this event the booking reminders above are unaffected
   * (AC-15.1).
   */
  public void onNoShowScored(CloudEvent event) {
    JsonNode data = event.data();
    if (!data.get("highRisk").asBoolean()) {
      return;
    }
    Instant appointmentStart = instant(data, "start");
    Instant dueAt = appointmentStart.minus(T_6H);
    Instant now = clock.instant();
    if (!dueAt.isAfter(now)) {
      return;
    }
    saveNew(
        List.of(
            reminder(
                subjectRef(text(data, "appointmentId")),
                text(data, "patientId"),
                ReminderKind.T_6H_RISK,
                appointmentStart,
                dueAt,
                now)));
  }

  private void addIfFuture(
      List<Reminder> reminders,
      String subjectRef,
      String patientId,
      ReminderKind kind,
      Instant appointmentStart,
      Instant dueAt,
      Instant now) {
    if (dueAt.isAfter(now)) {
      reminders.add(reminder(subjectRef, patientId, kind, appointmentStart, dueAt, now));
    }
  }

  private Reminder reminder(
      String subjectRef,
      String patientId,
      ReminderKind kind,
      Instant appointmentStart,
      Instant dueAt,
      Instant now) {
    return new Reminder(
        Uuids.v7(clock).toString(),
        subjectRef,
        patientId,
        kind,
        appointmentStart,
        dueAt,
        Reminder.STATUS_SCHEDULED,
        0,
        null,
        List.of(),
        now,
        now,
        null);
  }

  /**
   * Insert the milestones that the appointment does not have yet. Checked in the same transaction
   * as the insert; the unique index is the final guard when two deliveries race.
   */
  private void saveNew(List<Reminder> candidates) {
    if (candidates.isEmpty()) {
      return;
    }
    String subjectRef = candidates.get(0).subjectRef();
    MongoMessaging.inTransaction(
        tx,
        () -> {
          Set<String> existing =
              mongo
                  .find(
                      Query.query(Criteria.where("subjectRef").is(subjectRef)),
                      Document.class,
                      COLLECTION)
                  .stream()
                  .map(document -> document.getString("kind"))
                  .collect(Collectors.toSet());
          candidates.stream()
              .filter(reminder -> !existing.contains(reminder.kind().value()))
              .forEach(reminder -> mongo.insert(toDocument(reminder), COLLECTION));
          return null;
        });
  }

  private static Document toDocument(Reminder reminder) {
    return new Document("_id", reminder.id())
        .append("subjectRef", reminder.subjectRef())
        .append("patientId", reminder.patientId())
        .append("kind", reminder.kind().value())
        .append("appointmentStart", date(reminder.appointmentStart()))
        .append("dueAt", date(reminder.dueAt()))
        .append("status", reminder.status())
        .append("attempts", reminder.attempts())
        .append("lockedUntil", date(reminder.lockedUntil()))
        .append("deliveries", List.of())
        .append("createdAt", date(reminder.createdAt()))
        .append("updatedAt", date(reminder.updatedAt()))
        .append("completedAt", date(reminder.completedAt()));
  }

  private static Date date(Instant instant) {
    return instant == null ? null : Date.from(instant);
  }

  private static String subjectRef(String appointmentId) {
    return "Appointment/" + appointmentId;
  }

  private static String text(JsonNode data, String field) {
    JsonNode value = data.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("Event data is missing " + field);
    }
    return value.asString();
  }

  private static Instant instant(JsonNode data, String field) {
    return Instant.parse(text(data, field));
  }
}
