package org.shp.data.mongo;

import java.time.Clock;
import java.util.Date;
import org.bson.Document;
import org.shp.events.CloudEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inbox chống xử lý trùng trên MongoDB: {@code _id = "<consumer>|<messageId>"}, TTL 14 ngày (03
 * §2).
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
   * Xử lý event đúng một lần: transaction gồm dấu inbox + {@code handler} (dữ liệu và outbox của
   * handler dùng cùng transaction). Handler lỗi → rollback TOÀN BỘ (cả dấu inbox). Xung đột với lần
   * giao song song → thử lại, lần sau thấy dấu inbox → bỏ qua. Trả false nếu đã xử lý.
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
      // Hết lượt thử mà vẫn trùng khóa: nếu dấu inbox đã có thì event đã được xử lý ở nơi khác.
      if (mongo.findById(key, Document.class, MongoMessaging.INBOX) != null) {
        return false;
      }
      throw e;
    }
  }
}
