package org.shp.data.postgres;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.shp.events.CloudEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inbox that prevents duplicate processing: each (consumer, messageId) is processed only once, even
 * when the bus delivers it multiple times or several replicas receive it.
 */
public final class PostgresInbox {
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PostgresInbox(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Process an event exactly once: open transaction → record inbox marker → run {@code handler}
   * (using the same transaction for data and outbox) → commit. Handler failure → roll back
   * EVERYTHING (including the inbox marker), so a redelivery processes it again. Returns false if
   * already processed (skipped).
   */
  public boolean handleOnce(String consumer, CloudEvent evt, Runnable handler) {
    Boolean done =
        tx.execute(
            status -> {
              int inserted =
                  jdbc.sql(
                          "INSERT INTO inbox (consumer, message_id, processed_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                      .params(consumer, UUID.fromString(evt.id()), Timestamp.from(clock.instant()))
                      .update();
              if (inserted == 0) {
                return false;
              }
              handler.run();
              return true;
            });
    return Boolean.TRUE.equals(done);
  }
}
