package org.shp.data.mongo;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.Binary;
import org.shp.events.Uuids;
import org.shp.web.errors.ShpException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Idempotency-Key for MongoDB (task SHR-12). Call INSIDE {@link MongoMessaging#inTransaction} with
 * the data change: concurrent requests with the same key conflict, and the retry sees the existing
 * record commit → replay.
 */
public final class MongoIdempotency {
  public static final Duration RETENTION = Duration.ofHours(24);

  /** {@code replay=true}: already processed with {@code resourceId} — return the old result. */
  public record Claim(UUID resourceId, boolean replay) {}

  private final MongoTemplate mongo;
  private final Clock clock;

  public MongoIdempotency(MongoTemplate mongo, Clock clock) {
    this.mongo = mongo;
    this.clock = clock;
  }

  public Claim claim(String subject, String key, String operation, byte[] requestHash) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("claim must run in a transaction with the data change");
    }
    String id = subject + "|" + key;
    Instant now = clock.instant();
    Document existing = mongo.findById(id, Document.class, MongoMessaging.IDEMPOTENCY);
    if (existing != null
        && existing.getDate("createdAt").toInstant().isAfter(now.minus(RETENTION))) {
      boolean same =
          existing.getString("operation").equals(operation)
              && MessageDigest.isEqual(
                  existing.get("requestHash", Binary.class).getData(), requestHash);
      if (!same) {
        throw new ShpException("idempotency-key-reused");
      }
      return new Claim(UUID.fromString(existing.getString("resourceId")), true);
    }
    if (existing != null) {
      mongo.remove(
          MongoMessaging.id(id), MongoMessaging.IDEMPOTENCY); // older than 24 hours: treat as new
    }
    UUID resourceId = Uuids.v7(clock);
    mongo.insert(
        new Document("_id", id)
            .append("operation", operation)
            .append("requestHash", new Binary(requestHash))
            .append("resourceId", resourceId.toString())
            .append("createdAt", Date.from(now)),
        MongoMessaging.IDEMPOTENCY);
    return new Claim(resourceId, false);
  }
}
