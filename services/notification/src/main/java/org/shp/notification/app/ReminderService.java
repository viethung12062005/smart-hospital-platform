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
import org.springframework.data.mongodb.core.query.Update;
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

  public static final String APPOINTMENT_STATES = "reminder_appointment_states";

  public static final String CONSUMER = "notification";
  public static final String BOOKED_TYPE = "shp.scheduling.appointment-booked.v1";
  public static final String NO_SHOW_SCORED_TYPE = "shp.ml.no-show-scored.v1";
  public static final String CANCELLED_TYPE = "shp.scheduling.appointment-cancelled.v1";
  public static final String RESCHEDULED_TYPE = "shp.scheduling.appointment-rescheduled.v1";

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
    Instant now = clock.instant();

    saveNew(
        bookingReminders(
            subjectRef(text(data, "appointmentId")),
            text(data, "patientId"),
            instant(data, "start"),
            now));
  }

  /** E2: Cancel outstanding reminders and schedule one cancellation notice. */
  public void onCancelled(CloudEvent event) {
    JsonNode data = event.data();
    String subjectRef = subjectRef(text(data, "appointmentId"));
    String patientId = text(data, "patientId");
    Instant appointmentStart = instant(data, "start");
    Instant now = clock.instant();

    MongoMessaging.inTransaction(
        tx,
        () -> {
          recordClosedAppointment(subjectRef, "cancelled", now);
          cancelOutstanding(subjectRef, now, true);
          saveNew(
              List.of(
                  reminder(
                      subjectRef,
                      patientId,
                      ReminderKind.CANCELLATION,
                      appointmentStart,
                      now,
                      now)));
          return null;
        });
  }

  /**
   * E3: Retire old reminders and create the replacement appointment's confirmation and future
   * milestones as one transactional operation.
   */
  public void onRescheduled(CloudEvent event) {
    JsonNode data = event.data();
    String oldSubject = subjectRef(text(data, "oldAppointmentId"));
    String newSubject = subjectRef(text(data, "newAppointmentId"));
    String patientId = text(data, "patientId");
    Instant oldStart = instant(data, "oldStart");
    Instant newStart = instant(data, "newStart");
    Instant now = clock.instant();

    if (oldSubject.equals(newSubject)) {
      throw new IllegalArgumentException("Reschedule requires different appointment IDs");
    }

    MongoMessaging.inTransaction(
        tx,
        () -> {
          recordClosedAppointment(oldSubject, "rescheduled", now);
          cancelOutstanding(oldSubject, now, false);
          saveNew(bookingReminders(newSubject, patientId, newStart, now));

          mongo.updateFirst(
              Query.query(
                  Criteria.where("subjectRef")
                      .is(newSubject)
                      .and("kind")
                      .is(ReminderKind.BOOKING_CONFIRMATION.value())
                      .and("status")
                      .is(Reminder.STATUS_SCHEDULED)),
              new Update()
                  .set("noticeType", "reschedule")
                  .set("previousStart", Date.from(oldStart)),
              COLLECTION);

          return null;
        });
  }

  private List<Reminder> bookingReminders(
      String subjectRef, String patientId, Instant appointmentStart, Instant now) {
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

    return reminders;
  }

  private void recordClosedAppointment(String subjectRef, String state, Instant now) {
    Query query = Query.query(Criteria.where("_id").is(subjectRef));
    Update update = new Update().set("state", state).set("updatedAt", Date.from(now));

    mongo.upsert(query, update, APPOINTMENT_STATES);
  }

  private void cancelOutstanding(
      String subjectRef, Instant now, boolean preserveCancellationNotice) {
    Query query =
        Query.query(
            Criteria.where("subjectRef").is(subjectRef).and("status").in("scheduled", "sending"));

    if (preserveCancellationNotice) {
      query.addCriteria(Criteria.where("kind").ne(ReminderKind.CANCELLATION.value()));
    }

    Update update =
        new Update()
            .set("status", "cancelled")
            .set("lockedUntil", null)
            .set("updatedAt", Date.from(now))
            .set("completedAt", Date.from(now));

    mongo.updateMulti(query, update, COLLECTION);
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
          List<Document> stored =
              mongo.find(
                  Query.query(Criteria.where("subjectRef").is(subjectRef)),
                  Document.class,
                  COLLECTION);

          boolean appointmentCancelled =
              mongo.exists(Query.query(Criteria.where("_id").is(subjectRef)), APPOINTMENT_STATES)
                  || stored.stream()
                      .anyMatch(document ->
                          ReminderKind.CANCELLATION.value().equals(document.getString("kind"))
                              || "cancelled".equals(document.getString("status")));

          if (appointmentCancelled
              && candidates.stream()
                  .noneMatch(reminder -> reminder.kind() == ReminderKind.CANCELLATION)) {
            return null;
          }

          Set<String> existing =
              stored.stream()
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
