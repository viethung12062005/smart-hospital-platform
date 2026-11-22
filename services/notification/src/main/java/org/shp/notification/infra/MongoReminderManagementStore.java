package org.shp.notification.infra;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class MongoReminderManagementStore {

  private static final String COLLECTION = "reminders";

  private final MongoTemplate mongo;

  public MongoReminderManagementStore(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  /** N5: reminders belonging to one appointment. */
  public List<Document> findByAppointment(String appointmentId) {
    Query query = Query.query(Criteria.where("subjectRef").is("Appointment/" + appointmentId));

    query.with(Sort.by(Sort.Direction.ASC, "dueAt"));

    return mongo.find(query, Document.class, COLLECTION);
  }

  /** N6: permanently failed reminders, newest first. */
  public List<Document> findDeadLetters(int limit) {
    Query query = Query.query(Criteria.where("status").is("dead-lettered"));

    query.with(Sort.by(Sort.Direction.DESC, "completedAt"));
    query.limit(limit);

    return mongo.find(query, Document.class, COLLECTION);
  }

  /**
   * Atomically requeues a dead-lettered reminder if its appointment has not started.
   *
   * <p>Callers must independently verify that the appointment has not been cancelled. This method
   * must not be exposed directly as an HTTP retry endpoint.
   */
  public boolean retryDeadLetter(String reminderId, Instant now) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(reminderId)
                .and("status")
                .is("dead-lettered")
                .and("appointmentStart")
                .gt(Date.from(now)));

    Update update =
        new Update()
            .set("status", "scheduled")
            .set("attempts", 0)
            .set("dueAt", Date.from(now))
            .set("lockedUntil", null)
            .set("completedAt", null)
            .set("updatedAt", Date.from(now));

    return mongo.updateFirst(query, update, COLLECTION).getModifiedCount() == 1;
  }
}
