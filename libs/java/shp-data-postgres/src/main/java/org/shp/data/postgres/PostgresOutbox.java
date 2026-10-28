package org.shp.data.postgres;

import java.sql.Timestamp;
import java.util.UUID;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ghi event vào {@code outbox} TRONG CÙNG transaction với thay đổi dữ liệu (13-events §1): cả hai
 * cùng thành công hoặc cùng không.
 */
public final class PostgresOutbox {
  private final JdbcClient jdbc;

  public PostgresOutbox(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Phải gọi bên trong transaction ({@code @Transactional} hoặc {@code TransactionTemplate}). */
  public void add(CloudEvent evt) {
    add(evt, false);
  }

  /**
   * @param validate true: kiểm schema trước khi ghi (bật trong test/dev; production đã có test hợp
   *     đồng)
   */
  public void add(CloudEvent evt, boolean validate) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "PostgresOutbox.add phải chạy trong transaction cùng thay đổi dữ liệu");
    }
    if (validate) {
      var errors = EventSchemas.validate(evt);
      if (!errors.isEmpty()) {
        throw new IllegalArgumentException("Event không khớp schema: " + String.join("; ", errors));
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
