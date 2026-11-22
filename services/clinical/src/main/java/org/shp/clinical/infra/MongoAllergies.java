package org.shp.clinical.infra;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.shp.clinical.app.Allergies;
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

/**
 * {@code allergy_intolerances} through {@link MongoResourceStore}; the store is a private helper.
 */
@Repository
public class MongoAllergies implements Allergies {
  public static final String COLLECTION = "allergy_intolerances";
  static final String TYPE = "AllergyIntolerance";

  private final MongoTemplate mongo;
  private final MongoResourceStore store;

  public MongoAllergies(
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
    return store.find(TYPE, id).map(MongoAllergies::stored);
  }

  @Override
  public Stored update(
      Stored current, Document resource, Document search, int expectedVersion, String by) {
    return stored(
        store.update(
            new ResourceInput(TYPE, current.id(), current.patientId(), resource, search),
            expectedVersion,
            by,
            null));
  }

  @Override
  public List<Stored> byPatient(String patientId, int limit) {
    Query q =
        Query.query(Criteria.where("patientId").is(patientId).and("deleted").ne(true))
            .with(Sort.by(Sort.Direction.DESC, "search.recordedDate").and(Sort.by("_id")))
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
