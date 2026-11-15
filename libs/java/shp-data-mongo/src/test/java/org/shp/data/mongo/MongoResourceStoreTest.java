package org.shp.data.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.Uuids;
import org.shp.web.errors.ShpException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Mongo resource store: version CAS, history and outbox in one transaction (03 §2.5, SHR-10). */
class MongoResourceStoreTest extends MongoTestBase {
  static final Instant T0 = Instant.parse("2026-11-20T02:00:00Z");

  final MutableClock clock = new MutableClock(T0);
  final MongoResourceStore store = new MongoResourceStore(DB, TX, new MongoOutbox(DB), clock);

  static String newId() {
    return UUID.randomUUID().toString();
  }

  static MongoResourceStore.ResourceInput input(String id, String status, String patientId) {
    Document resource =
        new Document("resourceType", "Encounter")
            .append("id", id)
            .append("status", status)
            .append("subject", new Document("reference", "Patient/" + patientId));
    return new MongoResourceStore.ResourceInput(
        "Encounter",
        id,
        patientId,
        resource,
        new Document("status", status).append("practitionerId", "practitioner-" + patientId));
  }

  CloudEvent event(String encounterId, String patientId, int version) {
    return CloudEvents.create(
        "clinical",
        "shp.clinical.encounter-finished.v1",
        "Encounter/" + encounterId,
        Map.of(
            "encounterId",
            encounterId,
            "appointmentId",
            Uuids.v7(clock).toString(),
            "patientId",
            patientId,
            "practitionerId",
            Uuids.v7(clock).toString(),
            "finishedAt",
            "2026-12-03T02:00:00Z",
            "version",
            version),
        clock,
        null);
  }

  @Test
  void createWritesResourceHistoryAndOutboxInOneTransaction() {
    String id = newId();
    String patientId = newId();
    CloudEvent event = event(id, patientId, 1);

    var entry = store.create(input(id, "arrived", patientId), "nurse-1", event);

    assertThat(entry.versionId()).isEqualTo(1);
    assertThat(entry.lastUpdated()).isEqualTo(T0);

    Document stored = DB.findById(id, Document.class, "encounters");
    assertThat(stored).isNotNull();
    assertThat(stored.getInteger("versionId")).isEqualTo(1);
    assertThat(stored.getDate("lastUpdated").toInstant()).isEqualTo(T0);
    assertThat(stored.getString("patientId")).isEqualTo(patientId);
    assertThat(stored.getBoolean("deleted")).isFalse();
    assertThat(stored.get("resource", Document.class).getString("status")).isEqualTo("arrived");
    assertThat(stored.get("search", Document.class).getString("status")).isEqualTo("arrived");

    List<MongoResourceStore.History> history = store.history("Encounter", id);
    assertThat(history).hasSize(1);
    assertThat(history.get(0).versionId()).isEqualTo(1);
    assertThat(history.get(0).operation()).isEqualTo("create");
    assertThat(history.get(0).changedBy()).isEqualTo("nurse-1");
    assertThat(history.get(0).changedAt()).isEqualTo(T0);
    assertThat(history.get(0).patientId()).isEqualTo(patientId);
    assertThat(
            DB.findById("Encounter/" + id + "/1", Document.class, MongoMessaging.RESOURCE_HISTORY))
        .isNotNull();

    assertThat(count(MongoMessaging.OUTBOX)).isEqualTo(1);
    Document outboxEvent = DB.findById(event.id(), Document.class, MongoMessaging.OUTBOX);
    assertThat(outboxEvent).isNotNull();
    assertThat(outboxEvent.getString("eventType")).isEqualTo("shp.clinical.encounter-finished.v1");
  }

  @Test
  void updateWithTheCurrentVersionWritesANewHistoryVersion() {
    String id = newId();
    String patientId = newId();
    store.create(input(id, "arrived", patientId), "nurse-1", event(id, patientId, 1));

    clock.now = T0.plusSeconds(60);
    CloudEvent event = event(id, patientId, 2);
    var updated = store.update(input(id, "finished", patientId), 1, "doctor-1", event);

    assertThat(updated.versionId()).isEqualTo(2);
    assertThat(updated.lastUpdated()).isEqualTo(clock.now);

    Document stored = DB.findById(id, Document.class, "encounters");
    assertThat(stored.getInteger("versionId")).isEqualTo(2);
    assertThat(stored.getDate("lastUpdated").toInstant()).isEqualTo(clock.now);
    assertThat(stored.get("resource", Document.class).getString("status")).isEqualTo("finished");
    assertThat(stored.get("search", Document.class).getString("status")).isEqualTo("finished");

    List<MongoResourceStore.History> history = store.history("Encounter", id);
    assertThat(history).extracting(MongoResourceStore.History::versionId).containsExactly(1, 2);
    assertThat(history)
        .extracting(MongoResourceStore.History::operation)
        .containsExactly("create", "update");
    assertThat(history.get(1).changedBy()).isEqualTo("doctor-1");
    assertThat(history.get(1).resource().getString("status")).isEqualTo("finished");

    assertThat(count(MongoMessaging.OUTBOX)).isEqualTo(2);
    assertThat(DB.findById(event.id(), Document.class, MongoMessaging.OUTBOX)).isNotNull();
  }

  @Test
  void updateWithAStaleVersionMatchesZeroDocumentsAndIs412() {
    String id = newId();
    String patientId = newId();
    store.create(input(id, "arrived", patientId), "nurse-1", null);
    store.update(input(id, "in-progress", patientId), 1, "doctor-1", null);

    // The same compare-and-set the store runs: an old expected version matches 0 documents.
    UpdateResult stale =
        DB.updateFirst(
            Query.query(Criteria.where("_id").is(id).and("versionId").is(1)),
            new Update().inc("versionId", 1),
            "encounters");
    assertThat(stale.getMatchedCount()).isZero();
    assertThat(stale.getModifiedCount()).isZero();

    assertThatThrownBy(() -> store.update(input(id, "finished", patientId), 1, "doctor-1", null))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("version-conflict");
              assertThat(e.info().status()).isEqualTo(412);
            });

    Document stored = DB.findById(id, Document.class, "encounters");
    assertThat(stored.getInteger("versionId")).isEqualTo(2);
    assertThat(stored.get("resource", Document.class).getString("status")).isEqualTo("in-progress");
    assertThat(store.history("Encounter", id)).hasSize(2);
    assertThat(count(MongoMessaging.OUTBOX)).isZero();
  }

  @Test
  void updateOfAMissingResourceIs404() {
    String id = newId();

    assertThatThrownBy(() -> store.update(input(id, "arrived", newId()), 1, "doctor-1", null))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("not-found");
              assertThat(e.info().status()).isEqualTo(404);
            });
    assertThat(count(MongoMessaging.RESOURCE_HISTORY)).isZero();
    assertThat(count(MongoMessaging.OUTBOX)).isZero();
  }

  @Test
  void aFailureAfterTheCasUpdateRollsBackResourceHistoryAndOutbox() {
    String id = newId();
    String patientId = newId();
    store.create(input(id, "arrived", patientId), "nurse-1", null);
    // Occupy the history id of version 2, so the update's history insert fails after the CAS
    // update already matched and incremented the resource in the transaction.
    DB.insert(
        new Document("_id", "Encounter/" + id + "/2")
            .append("resourceType", "Encounter")
            .append("id", id)
            .append("versionId", 2)
            .append("operation", "fixture")
            .append("changedBy", "fixture")
            .append("changedAt", Date.from(clock.now))
            .append("resource", new Document("resourceType", "Encounter")),
        MongoMessaging.RESOURCE_HISTORY);

    assertThatThrownBy(() ->
            store.update(input(id, "finished", patientId), 1, "doctor-1", event(id, patientId, 2)))
        .isInstanceOf(DuplicateKeyException.class);

    // Full rollback: the CAS update, the new history entry and the outbox event are all gone.
    Document stored = DB.findById(id, Document.class, "encounters");
    assertThat(stored.getInteger("versionId")).isEqualTo(1);
    assertThat(stored.get("resource", Document.class).getString("status")).isEqualTo("arrived");
    assertThat(
            DB.findById("Encounter/" + id + "/2", Document.class, MongoMessaging.RESOURCE_HISTORY)
                .getString("operation"))
        .isEqualTo("fixture");
    assertThat(count(MongoMessaging.RESOURCE_HISTORY)).isEqualTo(2);
    assertThat(count(MongoMessaging.OUTBOX)).isZero();
  }

  @Test
  void aFailedOutboxInsertRollsBackTheCreate() {
    String id = newId();
    String patientId = newId();
    CloudEvent event = event(id, patientId, 1);
    DB.insert(
        new Document("_id", event.id())
            .append("eventType", event.type())
            .append("subject", event.subject())
            .append("payload", Document.parse(CloudEvents.toJson(event)))
            .append("occurredAt", Date.from(event.time()))
            .append("publishedAt", null)
            .append("attempts", 0),
        MongoMessaging.OUTBOX);

    assertThatThrownBy(() -> store.create(input(id, "arrived", patientId), "nurse-1", event))
        .isInstanceOf(DuplicateKeyException.class);

    assertThat(DB.findById(id, Document.class, "encounters")).isNull();
    assertThat(count(MongoMessaging.RESOURCE_HISTORY)).isZero();
    assertThat(count(MongoMessaging.OUTBOX)).isEqualTo(1); // the fixture only
  }

  @Test
  void findReturnsTheStoredResource() {
    String id = newId();
    String patientId = newId();
    store.create(input(id, "arrived", patientId), "nurse-1", null);

    var found = store.find("Encounter", id);
    assertThat(found).isPresent();
    assertThat(found.orElseThrow().versionId()).isEqualTo(1);
    assertThat(found.orElseThrow().patientId()).isEqualTo(patientId);
    assertThat(found.orElseThrow().resource().getString("status")).isEqualTo("arrived");
    assertThat(store.find("Encounter", newId())).isEmpty();
  }
}
