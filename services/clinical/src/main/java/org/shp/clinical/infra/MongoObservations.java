package org.shp.clinical.infra;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.shp.clinical.app.Observations;
import org.shp.data.mongo.MongoOutbox;
import org.shp.data.mongo.MongoResourceStore;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.data.mongo.MongoResourceStore.ResourceInput;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** {@code observations} through {@link MongoResourceStore}; the store is a private helper. */
@Repository
public class MongoObservations implements Observations {
  public static final String COLLECTION = "observations";
  static final String TYPE = "Observation";

  private final MongoTemplate mongo;
  private final MongoResourceStore store;

  public MongoObservations(
      MongoTemplate mongo, TransactionTemplate tx, MongoOutbox outbox, Clock clock) {
    this.mongo = mongo;
    this.store = new MongoResourceStore(mongo, tx, outbox, clock);
  }

  @Override
  public Stored create(
      String id, String patientId, Document resource, Document search, String changedBy) {
    return stored(
        store.create(new ResourceInput(TYPE, id, patientId, resource, search), changedBy, null));
  }

  @Override
  public Optional<Stored> find(String id) {
    return store.find(TYPE, id).map(MongoObservations::stored);
  }

  @Override
  public List<Stored> byPatient(
      String patientId, Collection<String> encounterIds, String category, String code, int limit) {
    Criteria c = Criteria.where("patientId").is(patientId).and("deleted").ne(true);
    if (encounterIds != null) {
      c = c.and("search.encounterId").in(encounterIds);
    }
    if (category != null) {
      c = c.and("search.category").is(category);
    }
    if (code != null) {
      c = c.and("search.code").is(code);
    }
    Query q =
        Query.query(c)
            .with(Sort.by(Sort.Direction.DESC, "search.effective").and(Sort.by("_id")))
            .limit(limit);
    return mongo.find(q, Document.class, COLLECTION).stream()
        .map(d ->
            new Stored(
                d.getString("_id"),
                d.getString("patientId"),
                ((Number) d.get("versionId")).intValue(),
                d.get("resource", Document.class),
                d.get("search", Document.class)))
        .toList();
  }

  private static Stored stored(Entry e) {
    return new Stored(e.id(), e.patientId(), e.versionId(), e.resource(), e.search());
  }
}
