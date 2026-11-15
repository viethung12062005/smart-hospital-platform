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
        Query.query(Criteria.where("status").is("scheduled").and("dueAt").lte(Date.from(now)));
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

    Query query =
        Query.query(Criteria.where("status").is("sending").and("lockedUntil").lt(Date.from(now)));

    Update update =
        new Update()
            .set("status", "scheduled")
            .set("lockedUntil", null)
            .set("updatedAt", Date.from(now));

    return mongoTemplate.updateMulti(query, update, COLLECTION).getModifiedCount();
  }
}
