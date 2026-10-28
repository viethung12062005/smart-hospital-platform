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
 * Tên collection, index nền tảng và cơ chế thử lại transaction dùng chung cho
 * outbox/inbox/idempotency trên MongoDB.
 */
public final class MongoMessaging {
  public static final String OUTBOX = "outbox";
  public static final String INBOX = "inbox";
  public static final String IDEMPOTENCY = "idempotency_keys";
  static final int MAX_ATTEMPTS = 8;

  private MongoMessaging() {}

  /**
   * Tạo index cần thiết (idempotent). Service gọi lúc khởi động hoặc trong script khởi tạo (03 §2,
   * 04 §1–2).
   */
  public static void ensureIndexes(MongoTemplate mongo) {
    mongo
        .indexOps(OUTBOX)
        .createIndex(
            new Index()
                .on("occurredAt", Sort.Direction.ASC)
                .named("ix_outbox_unpublished")
                .partial(PartialIndexFilter.of(Criteria.where("publishedAt").is(null))));
    mongo
        .indexOps(INBOX)
        .createIndex(
            new Index()
                .on("processedAt", Sort.Direction.ASC)
                .named("ttl_inbox_14d")
                .expire(14, TimeUnit.DAYS));
    mongo
        .indexOps(IDEMPOTENCY)
        .createIndex(
            new Index()
                .on("createdAt", Sort.Direction.ASC)
                .named("ttl_idempotency_24h")
                .expire(Duration.ofHours(24)));
  }

  /**
   * Chạy {@code work} trong transaction, THỬ LẠI khi MongoDB báo xung đột (nhãn {@code
   * TransientTransactionError}, WriteConflict) hoặc khi một transaction song song vừa ghi cùng khóa
   * (DuplicateKey) — đúng khuyến nghị của MongoDB cho transaction đa document.
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
