package org.shp.clinical.infra;

import java.time.Clock;
import org.bson.Document;
import org.shp.clinical.app.Encounters;
import org.shp.data.mongo.MongoOutbox;
import org.shp.data.mongo.MongoResourceStore;
import org.shp.data.mongo.MongoResourceStore.ResourceInput;
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
    store.create(new ResourceInput("Encounter", id, patientId, resource, search), changedBy, null);
  }
}
