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
 * Idempotency-Key cho MongoDB (task SHR-12). Gọi BÊN TRONG {@link MongoMessaging#inTransaction}
 * cùng thay đổi dữ liệu: request song song cùng key gây xung đột, lần thử lại thấy bản ghi đã
 * commit → replay.
 */
public final class MongoIdempotency {
  public static final Duration RETENTION = Duration.ofHours(24);

  /** {@code replay=true}: đã xử lý với {@code resourceId} — trả lại kết quả cũ. */
  public record Claim(UUID resourceId, boolean replay) {}

  private final MongoTemplate mongo;
  private final Clock clock;

  public MongoIdempotency(MongoTemplate mongo, Clock clock) {
    this.mongo = mongo;
    this.clock = clock;
  }

  public Claim claim(String subject, String key, String operation, byte[] requestHash) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("claim phải chạy trong transaction cùng thay đổi dữ liệu");
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
      mongo.remove(MongoMessaging.id(id), MongoMessaging.IDEMPOTENCY); // quá 24 giờ: coi như mới
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
