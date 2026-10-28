package org.shp.data.postgres;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.shp.events.CloudEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inbox chống xử lý trùng: mỗi (consumer, messageId) chỉ được xử lý một lần, kể cả khi bus giao
 * nhiều lần hoặc nhiều replica cùng nhận.
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
   * Xử lý event đúng một lần: mở transaction → ghi dấu inbox → chạy {@code handler} (dùng cùng
   * transaction cho dữ liệu và outbox) → commit. Handler lỗi → rollback TOÀN BỘ (cả dấu inbox) nên
   * lần giao lại sẽ xử lý lại. Trả false nếu đã xử lý (bỏ qua).
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
