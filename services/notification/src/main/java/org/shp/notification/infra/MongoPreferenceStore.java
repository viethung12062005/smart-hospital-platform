package org.shp.notification.infra;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.shp.notification.domain.NotificationPreferences;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Notification preferences (04 §1.2): one document per patient, {@code _id} = patientId. */
@Repository
public class MongoPreferenceStore {
  public static final String COLLECTION = "preferences";

  private final MongoTemplate mongo;

  public MongoPreferenceStore(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  /** Stored preferences with the version used for optimistic concurrency (07 §3). */
  public record Stored(NotificationPreferences preferences, long version) {}

  public Optional<Stored> find(String patientId) {
    Document doc = mongo.findById(patientId, Document.class, COLLECTION);
    if (doc == null) {
      return Optional.empty();
    }
    return Optional.of(
        new Stored(
            new NotificationPreferences(
                doc.getList("channels", String.class),
                doc.getString("locale"),
                doc.getString("quietStart"),
                doc.getString("quietEnd")),
            ((Number) doc.get("version")).longValue()));
  }

  /**
   * Compare-and-set by version: version 0 means "no document yet" (insert), any other value must
   * equal the stored version. Returns the new version, or empty when the version is stale.
   */
  public Optional<Long> save(
      String patientId, NotificationPreferences preferences, long expectedVersion, Instant now) {
    var channels = List.copyOf(preferences.channels());
    if (expectedVersion == 0) {
      var doc =
          new Document("_id", patientId)
              .append("channels", channels)
              .append("locale", preferences.locale())
              .append("version", 1L)
              .append("updatedAt", Date.from(now));
      if (preferences.hasQuietHours()) {
        doc.append("quietStart", preferences.quietStart())
            .append("quietEnd", preferences.quietEnd());
      }
      try {
        mongo.insert(doc, COLLECTION);
      } catch (DuplicateKeyException e) {
        return Optional.empty();
      }
      return Optional.of(1L);
    }
    Update update =
        new Update()
            .set("channels", channels)
            .set("locale", preferences.locale())
            .set("updatedAt", Date.from(now))
            .inc("version", 1);
    // Quiet hours are optional: clearing them must remove the stored values.
    if (preferences.hasQuietHours()) {
      update.set("quietStart", preferences.quietStart()).set("quietEnd", preferences.quietEnd());
    } else {
      update.unset("quietStart").unset("quietEnd");
    }
    Document updated =
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(patientId).and("version").is(expectedVersion)),
            update,
            FindAndModifyOptions.options().returnNew(true),
            Document.class,
            COLLECTION);
    return updated == null
        ? Optional.empty()
        : Optional.of(((Number) updated.get("version")).longValue());
  }
}
