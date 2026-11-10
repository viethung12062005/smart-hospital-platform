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
 * Idempotency-Key for PostgreSQL (07 §3, 02 §3.1). The claim lives in the SAME transaction as the
 * data change.
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
   * Claim processing rights for (subject, key). First time: insert the row, allocate a ResourceId.
   * Same key + action + body: replay. Different action or body: 422 {@code idempotency-key-reused}.
   * Concurrent requests with the same key: the later request waits for the earlier one then becomes
   * a replay (or claims again if the earlier rollback).
   */
  public IdempotencyClaim claim(String subject, String key, String operation, byte[] requestHash) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("claim must run in a transaction with the data change");
    }
    Instant now = clock.instant();
    // Rows older than 24 hours are treated as expired (the cleanup job only deletes them
    // physically).
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
