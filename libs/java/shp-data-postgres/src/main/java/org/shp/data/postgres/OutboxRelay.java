package org.shp.data.postgres;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.shp.events.CloudEvents;
import org.shp.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox relay → bus. Each round: lock up to {@code batchSize} unpublished rows with {@code FOR
 * UPDATE SKIP LOCKED} (many replicas run in parallel without duplicates), publish in time order,
 * mark {@code published_at}. Bus failure: record {@code attempts}/{@code last_error} (never the
 * payload), STOP the round (preserving order), retry with backoff. At-least-once delivery:
 * consumers must be idempotent.
 */
public final class OutboxRelay implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final EventPublisher publisher;
  private final Clock clock;
  private final int batchSize;
  private final Duration pollInterval;
  private final Duration maxBackoff;
  private final AtomicBoolean running = new AtomicBoolean();
  private Thread worker;

  public OutboxRelay(
      JdbcClient jdbc,
      TransactionTemplate tx,
      EventPublisher publisher,
      Clock clock,
      int batchSize,
      Duration pollInterval) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.publisher = publisher;
    this.clock = clock;
    this.batchSize = batchSize;
    this.pollInterval = pollInterval;
    this.maxBackoff = Duration.ofSeconds(30);
  }

  private record Row(UUID id, String payload) {}

  /**
   * One publish round; returns the number of events published. Throws {@link
   * OutboxPublishException} (after recording) if the bus fails midway.
   */
  public int relayOnce() {
    int[] sent = {0};
    RuntimeException[] failure = {null};
    tx.executeWithoutResult(
        status -> {
          List<Row> batch =
              jdbc.sql(
                      "SELECT id, payload::text FROM outbox WHERE published_at IS NULL ORDER BY occurred_at, id LIMIT ? FOR UPDATE SKIP LOCKED")
                  .param(batchSize)
                  .query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2)))
                  .list();
          for (Row row : batch) {
            try {
              publisher.publish(CloudEvents.parse(row.payload()));
            } catch (RuntimeException e) {
              failure[0] = e;
              String err = (e.getClass().getSimpleName() + ": " + e.getMessage());
              jdbc.sql("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?")
                  .params(err.length() > 500 ? err.substring(0, 500) : err, row.id())
                  .update();
              break;
            }
            jdbc.sql("UPDATE outbox SET published_at = ? WHERE id = ?")
                .params(Timestamp.from(clock.instant()), row.id())
                .update();
            sent[0]++;
          }
        }); // commit: keep the published rows and the failure counter
    if (failure[0] != null) {
      throw new OutboxPublishException(sent[0], failure[0]);
    }
    return sent[0];
  }

  @Override
  public void start() {
    if (running.compareAndSet(false, true)) {
      worker = Thread.ofVirtual().name("shp-outbox-relay").start(this::loop);
    }
  }

  @Override
  public void stop() {
    running.set(false);
    if (worker != null) {
      worker.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  private void loop() {
    Duration delay = pollInterval;
    while (running.get()) {
      try {
        int sent = relayOnce();
        delay =
            sent >= batchSize
                ? Duration.ZERO
                : pollInterval; // more work: publish again immediately
      } catch (RuntimeException e) {
        LOG.warn("Outbox relay failed; will retry with backoff", e);
        long next = Math.max(delay.toMillis(), pollInterval.toMillis()) * 2;
        delay = Duration.ofMillis(Math.min(next, maxBackoff.toMillis()));
      }
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** Bus rejected one event; earlier events in the round are still marked as published. */
  public static final class OutboxPublishException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int publishedBeforeFailure;

    OutboxPublishException(int publishedBeforeFailure, Throwable cause) {
      super("Could not publish event from outbox", cause);
      this.publishedBeforeFailure = publishedBeforeFailure;
    }

    public int publishedBeforeFailure() {
      return publishedBeforeFailure;
    }
  }
}
