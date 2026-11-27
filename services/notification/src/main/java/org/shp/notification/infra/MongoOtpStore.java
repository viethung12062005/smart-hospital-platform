package org.shp.notification.infra;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Storage of {@code otp_challenges} (only the code HMAC, never the code) and of {@code
 * otp_send_log} (one row per code sent, for the per-number and per-IP limits). Destinations and
 * addresses are stored only as keyed hashes.
 */
@Repository
public class MongoOtpStore {
  public static final String CHALLENGES = "otp_challenges";
  public static final String SEND_LOG = "otp_send_log";

  private final MongoTemplate mongo;

  public MongoOtpStore(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  public void insertChallenge(Document challenge) {
    mongo.insert(challenge, CHALLENGES);
  }

  public void removeChallenge(String id) {
    mongo.remove(Query.query(Criteria.where("_id").is(id)), CHALLENGES);
  }

  public Optional<Document> findChallenge(String id) {
    return Optional.ofNullable(mongo.findById(id, Document.class, CHALLENGES));
  }

  /** Adds a send-log row, returns its id. */
  public Object logSend(String destHash, String ipHash, Instant now) {
    Document row = new Document("destHash", destHash).append("createdAt", Date.from(now));
    if (ipHash != null) {
      row.append("ipHash", ipHash);
    }
    return mongo.insert(row, SEND_LOG).get("_id");
  }

  public void removeSend(Object id) {
    mongo.remove(Query.query(Criteria.where("_id").is(id)), SEND_LOG);
  }

  public long countSends(String field, String hash, Instant since) {
    return mongo.count(
        Query.query(Criteria.where(field).is(hash).and("createdAt").gte(Date.from(since))),
        SEND_LOG);
  }

  /** True when a challenge of this destination is locked at {@code now}. */
  public boolean isLocked(String destHash, Instant now) {
    return mongo.exists(
        Query.query(Criteria.where("destHash").is(destHash).and("lockedUntil").gt(Date.from(now))),
        CHALLENGES);
  }

  /**
   * Marks the challenge used. Returns false when it was already used (single use, race-safe) or
   * when it has reached {@code maxAttempts} wrong attempts, so a correct code that races with the
   * attempt that locks the challenge cannot succeed after the lock.
   */
  public boolean consume(String id, int maxAttempts, Instant now) {
    Document updated =
        mongo.findAndModify(
            Query.query(
                Criteria.where("_id")
                    .is(id)
                    .and("consumed")
                    .is(false)
                    .and("attempts")
                    .lt(maxAttempts)),
            new Update().set("consumed", true).set("consumedAt", Date.from(now)),
            FindAndModifyOptions.options().returnNew(true),
            Document.class,
            CHALLENGES);
    return updated != null;
  }

  /**
   * Counts one wrong attempt; at {@code maxAttempts} the challenge is locked until {@code now +
   * lock} and kept (TTL) until the lock ends. Returns true when this attempt locked it.
   */
  public boolean failAttempt(String id, int maxAttempts, Duration lock, Instant now) {
    Document updated =
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(id)),
            new Update().inc("attempts", 1),
            FindAndModifyOptions.options().returnNew(true),
            Document.class,
            CHALLENGES);
    if (updated == null || updated.getInteger("attempts", 0) < maxAttempts) {
      return false;
    }
    Date until = Date.from(now.plus(lock));
    mongo.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("lockedUntil", until).set("purgeAt", until),
        CHALLENGES);
    return true;
  }
}
