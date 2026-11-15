package org.shp.data.mongo;

import com.mongodb.MongoException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collection names, platform indexes and the transaction retry mechanism shared by
 * outbox/inbox/idempotency on MongoDB.
 */
public final class MongoMessaging {
  public static final String OUTBOX = "outbox";
  public static final String INBOX = "inbox";
  public static final String IDEMPOTENCY = "idempotency_keys";

  /** Version history shared by every resource collection (03 §2.2). */
  public static final String RESOURCE_HISTORY = "resource_history";

  static final int MAX_ATTEMPTS = 8;

  private MongoMessaging() {}

  /** The collections whose platform indexes a service needs (03 §2, 04 §1–2). */
  public enum Store {
    OUTBOX,
    INBOX,
    /** Only services that accept Idempotency-Key requests (scheduling, document). */
    IDEMPOTENCY,
    /** Resource version history, needed by every service that stores FHIR resources in MongoDB. */
    RESOURCE_HISTORY
  }

  /**
   * Create every platform index (idempotent). Kept for tests and tooling; a service should call
   * {@link #ensureIndexes(MongoTemplate, Store...)} with the stores it actually uses, so it does
   * not create collections it never writes.
   */
  public static void ensureIndexes(MongoTemplate mongo) {
    ensureIndexes(mongo, Store.values());
  }

  /**
   * Create the indexes of the named stores (idempotent). A service calls this at startup or in its
   * init script (03 §2, 04 §1–2).
   */
  public static void ensureIndexes(MongoTemplate mongo, Store... stores) {
    for (Store store : stores) {
      switch (store) {
        case OUTBOX ->
            mongo
                .indexOps(OUTBOX)
                .createIndex(
                    new Index()
                        .on("occurredAt", Sort.Direction.ASC)
                        .named("ix_outbox_unpublished")
                        .partial(PartialIndexFilter.of(Criteria.where("publishedAt").is(null))));
        case INBOX ->
            mongo
                .indexOps(INBOX)
                .createIndex(
                    new Index()
                        .on("processedAt", Sort.Direction.ASC)
                        .named("ttl_inbox_14d")
                        .expire(14, TimeUnit.DAYS));
        case IDEMPOTENCY ->
            mongo
                .indexOps(IDEMPOTENCY)
                .createIndex(
                    new Index()
                        .on("createdAt", Sort.Direction.ASC)
                        .named("ttl_idempotency_24h")
                        .expire(Duration.ofHours(24)));
        case RESOURCE_HISTORY ->
            mongo
                .indexOps(RESOURCE_HISTORY)
                .createIndex(
                    new Index()
                        .on("resourceType", Sort.Direction.ASC)
                        .on("id", Sort.Direction.ASC)
                        .on("versionId", Sort.Direction.DESC)
                        .named("ix_resource_history"));
      }
    }
  }

  /**
   * Run {@code work} in a transaction, RETRYING when MongoDB reports a conflict (label {@code
   * TransientTransactionError}, WriteConflict) or when a concurrent transaction just wrote the same
   * key (DuplicateKey) — exactly MongoDB's recommendation for multi-document transactions.
   */
  public static <T> T inTransaction(TransactionTemplate tx, Supplier<T> work) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return tx.execute(status -> work.get());
      } catch (RuntimeException e) {
        if (!retryable(e)) {
          throw e;
        }
        last = e;
        try {
          Thread.sleep(Math.min(5L * attempt * attempt, 100));
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
    throw last;
  }

  static boolean retryable(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof DuplicateKeyException) {
        return true;
      }
      if (t instanceof MongoException m
          && (m.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
              || m.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
              || m.getCode() == 112)) {
        return true;
      }
    }
    return false;
  }

  static org.springframework.data.mongodb.core.query.Query id(Object id) {
    return org.springframework.data.mongodb.core.query.Query.query(Criteria.where("_id").is(id));
  }
}
