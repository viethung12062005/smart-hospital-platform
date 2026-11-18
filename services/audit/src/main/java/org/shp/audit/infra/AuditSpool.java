package org.shp.audit.infra;

import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Durable waiting room for audit records between the bus and the WORM log. A record is stored here
 * in the same transaction as its inbox marker, so an acknowledged event is never only in memory.
 * The document {@code _id} is the {@code eventId}: the same event can never be spooled twice, even
 * after the inbox marker has expired. Records belong to the instance that received them and are
 * removed only after the batch holding them is in the WORM log.
 *
 * <p>A flush plan (one per instance) remembers which records are being written so that a crash
 * between the blob write and the clean-up neither loses nor repeats them.
 */
public class AuditSpool {

  public static final String SPOOL = "audit_spool";
  public static final String PLAN = "audit_flush_plan";

  private final MongoTemplate mongo;
  private final Clock clock;

  public AuditSpool(MongoTemplate mongo, Clock clock) {
    this.mongo = mongo;
    this.clock = clock;
  }

  /** Creates the collections and indexes (idempotent); transactions cannot create collections. */
  public void ensureCollections() {
    for (String name : new String[] {SPOOL, PLAN}) {
      if (!mongo.collectionExists(name)) {
        mongo.createCollection(name);
      }
    }
    mongo
        .indexOps(SPOOL)
        .createIndex(
            new Index()
                .on("instance", Sort.Direction.ASC)
                .on("receivedAt", Sort.Direction.ASC)
                .named("ix_spool_instance_received"));
  }

  /** Adds the record unless its eventId is already there. Joins the caller's transaction. */
  public void add(String eventId, String instance, String line) {
    mongo.upsert(
        Query.query(Criteria.where("_id").is(eventId)),
        new Update()
            .setOnInsert("instance", instance)
            .setOnInsert("receivedAt", Date.from(clock.instant()))
            .setOnInsert("line", line),
        SPOOL);
  }

  public long count(String instance) {
    return mongo.count(Query.query(Criteria.where("instance").is(instance)), SPOOL);
  }

  /** Receive time of the oldest waiting record. */
  public Optional<java.time.Instant> oldest(String instance) {
    Document d =
        mongo.findOne(
            Query.query(Criteria.where("instance").is(instance))
                .with(Sort.by("receivedAt", "_id"))
                .limit(1),
            Document.class,
            SPOOL);
    return Optional.ofNullable(d).map(x -> x.getDate("receivedAt").toInstant());
  }

  /** The next records in arrival order. */
  public List<Document> next(String instance, int limit) {
    return mongo.find(
        Query.query(Criteria.where("instance").is(instance))
            .with(Sort.by("receivedAt", "_id"))
            .limit(limit),
        Document.class,
        SPOOL);
  }

  public List<Document> byIds(List<String> ids) {
    List<Document> found =
        mongo.find(Query.query(Criteria.where("_id").in(ids)), Document.class, SPOOL);
    // keep the plan order: the hash depends on it
    return ids.stream()
        .map(id -> found.stream().filter(d -> id.equals(d.getString("_id"))).findFirst())
        .flatMap(Optional::stream)
        .toList();
  }

  /** The batch being written: its records in order and the chain head it is appended to. */
  public record Plan(List<String> ids, String headBefore) {}

  public Optional<Plan> plan(String instance) {
    Document d = mongo.findById(instance, Document.class, PLAN);
    return d == null
        ? Optional.empty()
        : Optional.of(new Plan(d.getList("ids", String.class), d.getString("headBefore")));
  }

  public void savePlan(String instance, Plan plan) {
    mongo.upsert(
        Query.query(Criteria.where("_id").is(instance)),
        new Update()
            .set("ids", plan.ids())
            .set("headBefore", plan.headBefore())
            .set("plannedAt", Date.from(clock.instant())),
        PLAN);
  }

  /** Removes the written records and the plan. */
  public void complete(String instance, List<String> ids) {
    mongo.remove(Query.query(Criteria.where("_id").in(ids)), SPOOL);
    mongo.remove(Query.query(Criteria.where("_id").is(instance)), PLAN);
  }
}
