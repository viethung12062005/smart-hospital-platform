package org.shp.scheduling.app;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily 02:00 (Asia/Ho_Chi_Minh) cleanup (02-db-scheduling.md §3.3). Deletes only data that is no
 * longer needed:
 *
 * <ul>
 *   <li>{@code outbox} rows that were <em>published</em> more than 7 days ago (unpublished rows are
 *       never touched);
 *   <li>{@code idempotency_key} rows created more than 24 hours ago;
 *   <li>{@code free} slots that ended more than 90 days ago and that no appointment references
 *       (booked, cancelled or no-show history keeps its slot).
 * </ul>
 *
 * Deletes run in batches of {@value #BATCH_SIZE} inside one transaction, and a transaction-scoped
 * advisory lock lets only one replica run at a time.
 */
@Component
public class DailyCleanupJob {

  private static final Logger LOG = LoggerFactory.getLogger(DailyCleanupJob.class);

  public static final String LOCK_KEY = "scheduling:daily-cleanup";
  public static final Duration OUTBOX_RETENTION = Duration.ofDays(7);
  public static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
  public static final Duration FREE_SLOT_RETENTION = Duration.ofDays(90);
  static final int BATCH_SIZE = 1000;

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  @Autowired
  public DailyCleanupJob(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.tx = Objects.requireNonNull(tx, "tx must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public record RunResult(
      boolean executed, int outboxDeleted, int idempotencyDeleted, int slotsDeleted) {}

  @Scheduled(cron = "${shp.scheduling.cleanup.cron:0 0 2 * * *}", zone = "Asia/Ho_Chi_Minh")
  public void runScheduled() {
    runCleanup();
  }

  /** Run the cleanup once, skipping if another replica holds the advisory lock. */
  public RunResult runCleanup() {
    // One transaction: the advisory lock lives as long as it, and a failure rolls everything back.
    RunResult result =
        tx.execute(status -> {
          Boolean acquired =
              jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")
                  .param(LOCK_KEY)
                  .query(Boolean.class)
                  .single();
          if (!Boolean.TRUE.equals(acquired)) {
            return new RunResult(false, 0, 0, 0);
          }
          Instant now = clock.instant();
          int outbox =
              deleteInBatches(
                  """
                  DELETE FROM outbox WHERE id IN (
                      SELECT id FROM outbox
                       WHERE published_at IS NOT NULL AND published_at < ?
                       LIMIT ?)
                  """,
                  now.minus(OUTBOX_RETENTION));
          int idempotency =
              deleteInBatches(
                  """
                  DELETE FROM idempotency_key WHERE (subject, key) IN (
                      SELECT subject, key FROM idempotency_key
                       WHERE created_at < ?
                       LIMIT ?)
                  """,
                  now.minus(IDEMPOTENCY_RETENTION));
          int slots =
              deleteInBatches(
                  """
                  DELETE FROM slot WHERE id IN (
                      SELECT s.id FROM slot s
                       WHERE s.status = 'free' AND s.end_at < ?
                         AND NOT EXISTS (SELECT 1 FROM appointment a WHERE a.slot_id = s.id)
                       LIMIT ?)
                  """,
                  now.minus(FREE_SLOT_RETENTION));
          return new RunResult(true, outbox, idempotency, slots);
        });
    if (result == null) {
      throw new IllegalStateException("Daily cleanup transaction returned null result");
    }
    if (result.executed()) {
      LOG.info(
          "Daily cleanup finished: outbox={}, idempotencyKeys={}, freeSlots={}",
          result.outboxDeleted(),
          result.idempotencyDeleted(),
          result.slotsDeleted());
    }
    return result;
  }

  private int deleteInBatches(String sql, Instant cutoff) {
    Supplier<Integer> batch =
        () -> jdbc.sql(sql).params(Timestamp.from(cutoff), BATCH_SIZE).update();
    int total = 0;
    int deleted;
    do {
      deleted = batch.get();
      total += deleted;
    } while (deleted == BATCH_SIZE);
    return total;
  }
}
