package org.shp.notification.infra;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.shp.notification.domain.NotificationPreferences;
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

  public Optional<NotificationPreferences> find(String patientId) {
    Document doc = mongo.findById(patientId, Document.class, COLLECTION);
    if (doc == null) {
      return Optional.empty();
    }
    return Optional.of(
        new NotificationPreferences(
            doc.getList("channels", String.class),
            doc.getString("locale"),
            doc.getString("quietStart"),
            doc.getString("quietEnd")));
  }

  public void save(String patientId, NotificationPreferences preferences, Instant now) {
    Update update =
        new Update()
            .set("channels", List.copyOf(preferences.channels()))
            .set("locale", preferences.locale())
            .set("updatedAt", Date.from(now));
    // Quiet hours are optional: clearing them must remove the stored values.
    if (preferences.hasQuietHours()) {
      update.set("quietStart", preferences.quietStart()).set("quietEnd", preferences.quietEnd());
    } else {
      update.unset("quietStart").unset("quietEnd");
    }
    mongo.findAndModify(
        Query.query(Criteria.where("_id").is(patientId)),
        update,
        FindAndModifyOptions.options().upsert(true),
        Document.class,
        COLLECTION);
  }
}
