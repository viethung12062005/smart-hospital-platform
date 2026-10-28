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
 * Relay outbox → bus cho MongoDB. MongoDB không có {@code SKIP LOCKED}: mỗi event được "giành"
 * nguyên tử bằng {@code findAndModify} (đặt {@code lockedUntil}, {@code lockedBy}), nên nhiều
 * replica không phát trùng; replica chết thì khóa hết hạn và event được giành lại. Bus lỗi: ghi
 * {@code attempts}/{@code lastError}, nhả khóa, DỪNG lượt (giữ thứ tự), thử lại với backoff.
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
   * Một lượt; trả số event đã phát. Ném {@link OutboxPublishException} (sau khi đã ghi nhận) nếu
   * bus lỗi giữa chừng.
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

  /** Bus từ chối một event; các event trước đó trong lượt vẫn được ghi nhận đã phát. */
  public static final class OutboxPublishException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int publishedBeforeFailure;

    OutboxPublishException(int publishedBeforeFailure, Throwable cause) {
      super("Không phát được event từ outbox", cause);
      this.publishedBeforeFailure = publishedBeforeFailure;
    }

    public int publishedBeforeFailure() {
      return publishedBeforeFailure;
    }
  }
}
