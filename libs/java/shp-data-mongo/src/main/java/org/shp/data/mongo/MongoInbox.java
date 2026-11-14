package org.shp.data.mongo;

import java.time.Clock;
import java.util.Date;
import org.bson.Document;
import org.shp.events.CloudEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Duplicate-processing guard inbox on MongoDB: {@code _id = "<consumer>|<messageId>"}, TTL 14 days
 * (03 §2).
 */
public final class MongoInbox {
  private final MongoTemplate mongo;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MongoInbox(MongoTemplate mongo, TransactionTemplate tx, Clock clock) {
    this.mongo = mongo;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Process an event exactly once: the transaction covers the inbox marker + {@code handler} (the
   * handler's data and outbox use the same transaction). Handler failure → roll back EVERYTHING
   * (including the inbox marker). A conflict with a concurrent run → retry; the next attempt sees
   * the inbox marker → skip. Returns false if already processed.
   */
  public boolean handleOnce(String consumer, CloudEvent evt, Runnable handler) {
    String key = consumer + "|" + evt.id();
    try {
      return MongoMessaging.inTransaction(
          tx,
          () -> {
            if (mongo.exists(
                org.springframework.data.mongodb.core.query.Query.query(
                    org.springframework.data.mongodb.core.query.Criteria.where("_id").is(key)),
                MongoMessaging.INBOX)) {
              return false;
            }
            mongo.insert(
                new Document("_id", key).append("processedAt", Date.from(clock.instant())),
                MongoMessaging.INBOX);
            handler.run();
            return true;
          });
    } catch (DuplicateKeyException e) {
      // Retries exhausted and still a duplicate key: if the inbox marker exists, the event was
      // processed elsewhere.
      if (mongo.findById(key, Document.class, MongoMessaging.INBOX) != null) {
        return false;
      }
      throw e;
    }
  }
}
