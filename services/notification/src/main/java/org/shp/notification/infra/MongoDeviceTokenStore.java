package org.shp.notification.infra;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Device tokens (04 §1.2): one document per push token, unique by token. */
@Repository
public class MongoDeviceTokenStore {
  public static final String COLLECTION = "device_tokens";

  private final MongoTemplate mongo;

  public MongoDeviceTokenStore(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  /**
   * Insert the token, or refresh it when it is already known. A known token keeps its id and
   * creation time; its owner and platform follow the latest registration (a device handed to
   * another user must not keep notifying the previous owner).
   */
  public String upsert(String token, String platform, String ownerRef, Instant now) {
    for (int attempt = 0; ; attempt++) {
      try {
        Update update =
            new Update()
                .setOnInsert("_id", UUID.randomUUID().toString())
                .setOnInsert("createdAt", Date.from(now))
                .set("ownerRef", ownerRef)
                .set("platform", platform)
                .set("lastSeenAt", Date.from(now));
        Document saved =
            mongo.findAndModify(
                Query.query(Criteria.where("token").is(token)),
                update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                Document.class,
                COLLECTION);
        return saved.getString("_id");
      } catch (DuplicateKeyException e) {
        // Two first registrations of the same token raced; the loser retries as an update.
        if (attempt >= 1) {
          throw e;
        }
      }
    }
  }

  public Optional<Document> find(String tokenId) {
    return Optional.ofNullable(mongo.findById(tokenId, Document.class, COLLECTION));
  }

  public boolean delete(String tokenId) {
    return mongo
            .remove(Query.query(Criteria.where("_id").is(tokenId)), COLLECTION)
            .getDeletedCount()
        > 0;
  }
}
