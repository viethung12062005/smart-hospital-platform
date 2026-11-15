package org.shp.data.postgres;

import java.sql.Timestamp;
import java.util.UUID;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Write the event to {@code outbox} IN THE SAME transaction as the data change (13-events §1): both
 * succeed or both fail.
 */
public final class PostgresOutbox {
  private final JdbcClient jdbc;

  public PostgresOutbox(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Must be called inside a transaction ({@code @Transactional} or {@code TransactionTemplate}).
   */
  public void add(CloudEvent evt) {
    add(evt, false);
  }

  /**
   * @param validate true: validate the schema before writing (enabled in test/dev; production
   *     already has contract tests)
   */
  public void add(CloudEvent evt, boolean validate) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "PostgresOutbox.add must run in a transaction with the data change");
    }
    if (validate) {
      var errors = EventSchemas.validate(evt);
      if (!errors.isEmpty()) {
        throw new IllegalArgumentException(
            "Event does not match schema: " + String.join("; ", errors));
      }
    }
    jdbc.sql(
            "INSERT INTO outbox (id, event_type, subject, payload, occurred_at) VALUES (?, ?, ?, ?::jsonb, ?)")
        .params(
            UUID.fromString(evt.id()),
            evt.type(),
            evt.subject(),
            CloudEvents.toJson(evt),
            Timestamp.from(evt.time()))
        .update();
  }
}
