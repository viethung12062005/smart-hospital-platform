package org.shp.data.mongo;

import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.shp.events.CloudEvents;
import org.shp.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Outbox relay → bus for MongoDB. MongoDB has no {@code SKIP LOCKED}: each event is "claimed"
 * atomically via {@code findAndModify} (setting {@code lockedUntil}, {@code lockedBy}), so multiple
 * replicas do not publish duplicates; if a replica dies the lock expires and the event is
 * reclaimed. Bus failure: record {@code attempts}/{@code lastError}, release the lock, STOP the
 * round (preserving order), retry with backoff.
 */
public final class MongoOutboxRelay implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(MongoOutboxRelay.class);
  static final Duration LEASE = Duration.ofSeconds(30);

  private final MongoTemplate mongo;
  private final EventPublisher publisher;
  private final Clock clock;
  private final int batchSize;
  private final Duration pollInterval;
  private final String instance = UUID.randomUUID().toString();
  private final AtomicBoolean running = new AtomicBoolean();
  private Thread worker;

  public MongoOutboxRelay(
      MongoTemplate mongo,
      EventPublisher publisher,
      Clock clock,
      int batchSize,
      Duration pollInterval) {
    this.mongo = mongo;
    this.publisher = publisher;
    this.clock = clock;
    this.batchSize = batchSize;
    this.pollInterval = pollInterval;
  }

  /**
   * One round; returns the number of events published. Throws {@link OutboxPublishException} (after
   * recording) if the bus fails midway.
   */
  public int relayOnce() {
    int sent = 0;
    while (sent < batchSize) {
      Date now = Date.from(clock.instant());
      Query next =
          Query.query(
                  Criteria.where("publishedAt")
                      .is(null)
                      .orOperator(
                          Criteria.where("lockedUntil").is(null),
                          Criteria.where("lockedUntil").lt(now)))
              .with(Sort.by("occurredAt", "_id"));
      Document claimed =
          mongo.findAndModify(
              next,
              new Update()
                  .set("lockedUntil", Date.from(clock.instant().plus(LEASE)))
                  .set("lockedBy", instance),
              FindAndModifyOptions.options().returnNew(true),
              Document.class,
              MongoMessaging.OUTBOX);
      if (claimed == null) {
        return sent;
      }
      Object id = claimed.get("_id");
      try {
        publisher.publish(CloudEvents.parse(claimed.get("payload", Document.class).toJson()));
      } catch (RuntimeException e) {
        String err = e.getClass().getSimpleName() + ": " + e.getMessage();
        mongo.updateFirst(
            Query.query(Criteria.where("_id").is(id)),
            new Update()
                .inc("attempts", 1)
                .set("lastError", err.length() > 500 ? err.substring(0, 500) : err)
                .unset("lockedUntil")
                .unset("lockedBy"),
            MongoMessaging.OUTBOX);
        throw new OutboxPublishException(sent, e);
      }
      mongo.updateFirst(
          Query.query(Criteria.where("_id").is(id).and("lockedBy").is(instance)),
          new Update()
              .set("publishedAt", Date.from(clock.instant()))
              .unset("lockedUntil")
              .unset("lockedBy"),
          MongoMessaging.OUTBOX);
      sent++;
    }
    return sent;
  }

  @Override
  public void start() {
    if (running.compareAndSet(false, true)) {
      worker = Thread.ofVirtual().name("shp-mongo-outbox-relay").start(this::loop);
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
        delay = relayOnce() >= batchSize ? Duration.ZERO : pollInterval;
      } catch (RuntimeException e) {
        LOG.warn("Mongo outbox relay failed; will retry with backoff", e);
        delay =
            Duration.ofMillis(
                Math.min(Math.max(delay.toMillis(), pollInterval.toMillis()) * 2, 30_000));
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
