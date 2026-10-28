package org.shp.data.postgres;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.shp.events.Uuids;
import org.shp.web.errors.ShpException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Idempotency-Key cho PostgreSQL (07 §3, 02 §3.1). Claim nằm trong CÙNG transaction với thay đổi dữ
 * liệu.
 */
public final class PostgresIdempotency {
  public static final Duration RETENTION = Duration.ofHours(24);

  private final JdbcClient jdbc;
  private final Clock clock;

  public PostgresIdempotency(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Giành quyền xử lý (subject, key). Lần đầu: ghi dòng, cấp sẵn ResourceId. Cùng key + thao tác +
   * body: replay. Khác thao tác hoặc body: 422 {@code idempotency-key-reused}. Request song song
   * cùng key: request sau chờ request trước rồi thành replay (hoặc giành lại nếu request trước
   * rollback).
   */
  public IdempotencyClaim claim(String subject, String key, String operation, byte[] requestHash) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("claim phải chạy trong transaction cùng thay đổi dữ liệu");
    }
    Instant now = clock.instant();
    // Dòng quá 24 giờ coi như hết hạn (job dọn dẹp chỉ xóa vật lý).
    jdbc.sql("DELETE FROM idempotency_key WHERE subject = ? AND key = ? AND created_at < ?")
        .params(subject, key, Timestamp.from(now.minus(RETENTION)))
        .update();
    UUID resourceId = Uuids.v7(clock);
    int inserted =
        jdbc.sql(
                "INSERT INTO idempotency_key (subject, key, operation, request_hash, resource_id, created_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (subject, key) DO NOTHING")
            .params(subject, key, operation, requestHash, resourceId, Timestamp.from(now))
            .update();
    if (inserted == 1) {
      return new IdempotencyClaim(resourceId, false);
    }
    record Existing(String operation, byte[] hash, UUID resourceId) {}
    Existing e =
        jdbc.sql(
                "SELECT operation, request_hash, resource_id FROM idempotency_key WHERE subject = ? AND key = ?")
            .params(subject, key)
            .query(
                (rs, i) ->
                    new Existing(rs.getString(1), rs.getBytes(2), rs.getObject(3, UUID.class)))
            .single();
    if (!e.operation().equals(operation) || !MessageDigest.isEqual(e.hash(), requestHash)) {
      throw new ShpException("idempotency-key-reused");
    }
    return new IdempotencyClaim(e.resourceId(), true);
  }
}
