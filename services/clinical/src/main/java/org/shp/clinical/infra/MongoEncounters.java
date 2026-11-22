package org.shp.clinical.infra;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.shp.clinical.app.Encounters;
import org.shp.data.mongo.MongoOutbox;
import org.shp.data.mongo.MongoResourceStore;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.data.mongo.MongoResourceStore.ResourceInput;
import org.shp.events.CloudEvent;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code encounters} through {@link MongoResourceStore} (resource + {@code resource_history} in one
 * transaction). The store is a stateless helper created here rather than a shared bean, so this
 * class does not collide with other clinical configurations that declare one.
 */
@Repository
public class MongoEncounters implements Encounters {
  public static final String COLLECTION = "encounters";
  static final String TYPE = "Encounter";

  private final MongoTemplate mongo;
  private final MongoResourceStore store;

  public MongoEncounters(
      MongoTemplate mongo, TransactionTemplate tx, MongoOutbox outbox, Clock clock) {
    this.mongo = mongo;
    this.store = new MongoResourceStore(mongo, tx, outbox, clock);
  }

  @Override
  public boolean existsForAppointment(String appointmentId) {
    return mongo.exists(
        Query.query(Criteria.where("search.appointmentId").is(appointmentId)), COLLECTION);
  }

  @Override
  public void create(
      String id, String patientId, Document resource, Document search, String changedBy) {
    // Joins the inbox transaction (propagation REQUIRED): the Encounter, its history entry and the
    // inbox record commit together.
    store.create(new ResourceInput(TYPE, id, patientId, resource, search), changedBy, null);
  }

  @Override
  public Optional<Stored> find(String id) {
    return store.find(TYPE, id).map(MongoEncounters::stored);
  }

  @Override
  public Stored update(
      Stored current,
      Document resource,
      Document search,
      int expectedVersion,
      String changedBy,
      CloudEvent event) {
    Entry e =
        store.update(
            new ResourceInput(TYPE, current.id(), current.patientId(), resource, search),
            expectedVersion,
            changedBy,
            event);
    return stored(e);
  }

  @Override
  public List<Stored> byPatient(String patientId, String status, int limit) {
    Criteria c = Criteria.where("patientId").is(patientId).and("deleted").ne(true);
    if (status != null) {
      c = c.and("search.status").is(status);
    }
    Query q =
        Query.query(c)
            .with(Sort.by(Sort.Direction.DESC, "search.periodStart").and(Sort.by("_id")))
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
