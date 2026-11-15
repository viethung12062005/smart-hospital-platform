package org.shp.data.mongo;

import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.shp.events.CloudEvent;
import org.shp.web.errors.ShpException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FHIR resource store on MongoDB (03 §2.1, §2.5; task SHR-10). One collection per resource type
 * holds the administrative part (id, version, partition key), the full FHIR {@code resource} and
 * the normalized {@code search} fields.
 *
 * <p>Every write runs in ONE transaction: resource, {@code resource_history} and the outbox event.
 * An update is a compare-and-set on {@code versionId}: a stale expected version matches 0 documents
 * and raises {@code version-conflict}; any failure inside the transaction rolls back all three
 * writes.
 */
public final class MongoResourceStore {

  /**
   * The resource collections of the clinical and document databases (03 §2.2, §3). Scheduling
   * resources live in PostgreSQL and are not stored here.
   */
  private static final Map<String, String> COLLECTIONS =
      Map.of(
          "Patient", "patients",
          "Practitioner", "practitioners",
          "PractitionerRole", "practitioner_roles",
          "Encounter", "encounters",
          "Observation", "observations",
          "Condition", "conditions",
          "AllergyIntolerance", "allergy_intolerances",
          "DocumentReference", "document_references");

  private final MongoTemplate mongo;
  private final TransactionTemplate tx;
  private final MongoOutbox outbox;
  private final Clock clock;

  public MongoResourceStore(
      MongoTemplate mongo, TransactionTemplate tx, MongoOutbox outbox, Clock clock) {
    this.mongo = Objects.requireNonNull(mongo, "mongo must not be null");
    this.tx = Objects.requireNonNull(tx, "tx must not be null");
    this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** The values written to one resource collection on create or update. */
  public record ResourceInput(
      String resourceType, String id, String patientId, Document resource, Document search) {
    public ResourceInput {
      if (resourceType == null || resourceType.isBlank()) {
        throw new IllegalArgumentException("resourceType must not be blank");
      }
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("id must not be blank");
      }
      resource = Objects.requireNonNull(resource, "resource must not be null");
      search = Objects.requireNonNull(search, "search must not be null");
      if (!resourceType.equals(resource.get("resourceType"))) {
        throw new IllegalArgumentException("resource.resourceType must be " + resourceType);
      }
    }
  }

  /** A stored resource: the administrative part plus the FHIR resource and search fields. */
  public record Entry(
      String resourceType,
      String id,
      String patientId,
      int versionId,
      Instant lastUpdated,
      Document resource,
      Document search) {}

  /** One immutable version in {@code resource_history}. */
  public record History(
      String resourceType,
      String id,
      String patientId,
      int versionId,
      String operation,
      String changedBy,
      Instant changedAt,
      Document resource) {}

  /**
   * Insert a new resource at version 1, its {@code create} history entry and (when given) the
   * outbox event, in one transaction. Returns the stored entry.
   */
  public Entry create(ResourceInput input, String changedBy, CloudEvent event) {
    requireChangedBy(changedBy);
    String collection = collectionOf(input.resourceType());
    Instant now = clock.instant();
    return MongoMessaging.inTransaction(
        tx,
        () -> {
          Document document =
              new Document("_id", input.id())
                  .append("versionId", 1)
                  .append("lastUpdated", Date.from(now))
                  .append("resource", input.resource())
                  .append("search", input.search())
                  .append("deleted", false);
          if (input.patientId() != null) {
            document.append("patientId", input.patientId());
          }
          mongo.insert(document, collection);
          writeHistory(input, 1, "create", changedBy, now);
          if (event != null) {
            outbox.add(event);
          }
          return entry(input, 1, now);
        });
  }

  /**
   * Update a resource with optimistic concurrency: only a document whose {@code versionId} equals
   * {@code expectedVersion} is changed, and its version is incremented. The new history entry and
   * (when given) the outbox event are written in the same transaction.
   *
   * @throws ShpException {@code version-conflict} (HTTP 412) when the stored version differs, or
   *     {@code not-found} (HTTP 404) when the resource does not exist
   */
  public Entry update(
      ResourceInput input, int expectedVersion, String changedBy, CloudEvent event) {
    requireChangedBy(changedBy);
    if (expectedVersion < 1) {
      throw new IllegalArgumentException("expectedVersion must be >= 1");
    }
    String collection = collectionOf(input.resourceType());
    int newVersion = expectedVersion + 1;
    Instant now = clock.instant();
    return MongoMessaging.inTransaction(
        tx,
        () -> {
          UpdateResult result =
              mongo.updateFirst(
                  Query.query(
                      Criteria.where("_id").is(input.id()).and("versionId").is(expectedVersion)),
                  new Update()
                      .set("resource", input.resource())
                      .set("search", input.search())
                      .set("lastUpdated", Date.from(now))
                      .inc("versionId", 1),
                  collection);
          if (result.getMatchedCount() == 0) {
            // A stale version matched 0 documents; distinguish it from a resource that is gone.
            boolean exists =
                mongo.exists(Query.query(Criteria.where("_id").is(input.id())), collection);
            throw new ShpException(exists ? "version-conflict" : "not-found");
          }
          writeHistory(input, newVersion, "update", changedBy, now);
          if (event != null) {
            outbox.add(event);
          }
          return entry(input, newVersion, now);
        });
  }

  /** Read one resource by id. */
  public Optional<Entry> find(String resourceType, String id) {
    Document document = mongo.findById(id, Document.class, collectionOf(resourceType));
    return document == null
        ? Optional.empty()
        : Optional.of(
            new Entry(
                resourceType,
                document.getString("_id"),
                document.getString("patientId"),
                document.getInteger("versionId"),
                instant(document.getDate("lastUpdated")),
                document.get("resource", Document.class),
                document.get("search", Document.class)));
  }

  /** All history versions of one resource, oldest first (03 §2.2). */
  public List<History> history(String resourceType, String id) {
    return mongo
        .find(
            Query.query(Criteria.where("resourceType").is(resourceType).and("id").is(id))
                .with(Sort.by(Sort.Direction.ASC, "versionId")),
            Document.class,
            MongoMessaging.RESOURCE_HISTORY)
        .stream()
        .map(document ->
            new History(
                document.getString("resourceType"),
                document.getString("id"),
                document.getString("patientId"),
                document.getInteger("versionId"),
                document.getString("operation"),
                document.getString("changedBy"),
                instant(document.getDate("changedAt")),
                document.get("resource", Document.class)))
        .toList();
  }

  private void writeHistory(
      ResourceInput input, int version, String operation, String changedBy, Instant now) {
    Document document =
        new Document("_id", historyId(input.resourceType(), input.id(), version))
            .append("resourceType", input.resourceType())
            .append("id", input.id())
            .append("versionId", version)
            .append("operation", operation)
            .append("changedBy", changedBy)
            .append("changedAt", Date.from(now))
            .append("resource", input.resource());
    if (input.patientId() != null) {
      document.append("patientId", input.patientId());
    }
    mongo.insert(document, MongoMessaging.RESOURCE_HISTORY);
  }

  private static Entry entry(ResourceInput input, int version, Instant now) {
    return new Entry(
        input.resourceType(),
        input.id(),
        input.patientId(),
        version,
        now,
        input.resource(),
        input.search());
  }

  private static String historyId(String resourceType, String id, int version) {
    return resourceType + "/" + id + "/" + version;
  }

  private static String collectionOf(String resourceType) {
    String collection = COLLECTIONS.get(resourceType);
    if (collection == null) {
      throw new IllegalArgumentException("Resource type is not stored in MongoDB: " + resourceType);
    }
    return collection;
  }

  private static Instant instant(Date date) {
    return date == null ? null : date.toInstant();
  }

  private static void requireChangedBy(String changedBy) {
    if (changedBy == null || changedBy.isBlank()) {
      throw new IllegalArgumentException("changedBy must not be blank");
    }
  }
}
