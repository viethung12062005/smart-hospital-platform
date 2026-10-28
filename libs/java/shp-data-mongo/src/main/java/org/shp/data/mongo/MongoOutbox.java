package org.shp.data.mongo;

import java.util.Date;
import org.bson.Document;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Ghi event vào {@code outbox} TRONG CÙNG transaction với thay đổi dữ liệu (03 §2.5, 13 §1). */
public final class MongoOutbox {
  private final MongoTemplate mongo;

  public MongoOutbox(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  public void add(CloudEvent evt) {
    add(evt, false);
  }

  /**
   * @param validate true: kiểm schema trước khi ghi (bật trong test/dev)
   */
  public void add(CloudEvent evt, boolean validate) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "MongoOutbox.add phải chạy trong transaction cùng thay đổi dữ liệu");
    }
    if (validate) {
      var errors = EventSchemas.validate(evt);
      if (!errors.isEmpty()) {
        throw new IllegalArgumentException("Event không khớp schema: " + String.join("; ", errors));
      }
    }
    mongo.insert(
        new Document("_id", evt.id())
            .append("eventType", evt.type())
            .append("subject", evt.subject())
            .append("payload", Document.parse(CloudEvents.toJson(evt)))
            .append("occurredAt", Date.from(evt.time()))
            .append("publishedAt", null)
            .append("attempts", 0),
        MongoMessaging.OUTBOX);
  }
}
