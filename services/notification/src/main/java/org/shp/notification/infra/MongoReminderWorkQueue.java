package org.shp.notification.infra;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class MongoReminderWorkQueue {

  private static final String COLLECTION = "reminders";
  private static final Duration LOCK_DURATION = Duration.ofMinutes(2);

  private final MongoTemplate mongoTemplate;

  public MongoReminderWorkQueue(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * Atomically claims the earliest due reminder. MongoDB guarantees atomicity for this
   * single-document update.
   */
  public Optional<Document> claimNext() {
    Instant now = Instant.now();
    Query query =
        Query.query(
            Criteria.where("status")
                .is("scheduled")
                .and("dueAt")
                .lte(Date.from(now))
                .and("attempts")
                .lt(5));
    query.with(Sort.by(Sort.Direction.ASC, "dueAt"));

    Update update =
        new Update()
            .set("status", "sending")
            .set("lockedUntil", Date.from(now.plus(LOCK_DURATION)))
            .set("updatedAt", Date.from(now))
            .inc("attempts", 1);

    Document claimed =
        mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true),
            Document.class,
            COLLECTION);

    return Optional.ofNullable(claimed);
  }

  /** Releases reminders whose workers stopped before completing them. */
  public long recoverExpiredClaims() {
    Instant now = Instant.now();

    Query exhausted =
        Query.query(
            Criteria.where("status")
                .is("sending")
                .and("lockedUntil")
                .lt(Date.from(now))
                .and("attempts")
                .gte(5));

    Update deadLetter =
        new Update()
            .set("status", "dead-lettered")
            .set("lockedUntil", null)
            .set("updatedAt", Date.from(now))
            .set("completedAt", Date.from(now));

    long deadLettered =
        mongoTemplate.updateMulti(exhausted, deadLetter, COLLECTION).getModifiedCount();

    Query recoverable =
        Query.query(
            Criteria.where("status")
                .is("sending")
                .and("lockedUntil")
                .lt(Date.from(now))
                .and("attempts")
                .lt(5));

    Update requeue =
        new Update()
            .set("status", "scheduled")
            .set("lockedUntil", null)
            .set("updatedAt", Date.from(now));

    long recovered = mongoTemplate.updateMulti(recoverable, requeue, COLLECTION).getModifiedCount();

    return deadLettered + recovered;
  }

  /**
   * Persists the result of one claimed attempt.
   *
   * <p>The attempt number and lock must still match, so an old worker cannot overwrite a reminder
   * reclaimed by another worker.
   */
  public boolean finishAttempt(
      Document claimed,
      java.util.List<Document> deliveries,
      Instant finishedAt,
      boolean sent,
      boolean permanentFailure) {

    int attempt = ((Number) claimed.get("attempts")).intValue();

    if (attempt < 1 || attempt > 5) {
      throw new IllegalArgumentException("Attempt must be between 1 and 5");
    }

    Query query =
        Query.query(
            Criteria.where("_id")
                .is(claimed.get("_id"))
                .and("status")
                .is("sending")
                .and("attempts")
                .is(attempt)
                .and("lockedUntil")
                .is(claimed.get("lockedUntil"))
                .gt(Date.from(Instant.now())));

    Update update = new Update().set("updatedAt", Date.from(finishedAt)).set("lockedUntil", null);

    if (!deliveries.isEmpty()) {
      update.push("deliveries").each(deliveries.toArray());
    }

    if (sent) {
      update.set("status", "sent");
      update.set("completedAt", Date.from(finishedAt));
    } else if (permanentFailure || attempt == 5) {
      update.set("status", "dead-lettered");
      update.set("completedAt", Date.from(finishedAt));
    } else {
      long[] backoffMinutes = {1, 5, 15, 60};
      update.set("status", "scheduled");
      update.set(
          "dueAt", Date.from(finishedAt.plus(Duration.ofMinutes(backoffMinutes[attempt - 1]))));
    }

    return mongoTemplate.updateFirst(query, update, COLLECTION).getModifiedCount() == 1;
  }
}
