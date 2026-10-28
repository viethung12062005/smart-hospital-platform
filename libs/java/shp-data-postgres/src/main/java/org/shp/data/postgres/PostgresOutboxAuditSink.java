package org.shp.data.postgres;

import java.time.Clock;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.AuditSink;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ghi bản ghi audit thành event E11 vào outbox trong transaction RIÊNG (REQUIRES_NEW): audit của
 * một lần truy cập vẫn được lưu kể cả khi giao dịch nghiệp vụ rollback, và không mất khi bus lỗi
 * (relay phát sau).
 */
public final class PostgresOutboxAuditSink implements AuditSink {
  private final PostgresOutbox outbox;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PostgresOutboxAuditSink(PostgresOutbox outbox, TransactionTemplate tx, Clock clock) {
    this.outbox = outbox;
    this.tx = new TransactionTemplate(tx.getTransactionManager());
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  @Override
  public void emit(AuditRecord record) {
    tx.executeWithoutResult(s -> outbox.add(record.toCloudEvent(clock)));
  }
}
