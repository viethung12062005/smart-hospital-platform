package org.shp.data.mongo;

import java.time.Clock;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.AuditSink;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ghi audit thành event E11 vào outbox trong transaction RIÊNG; xem {@code
 * PostgresOutboxAuditSink}.
 */
public final class MongoOutboxAuditSink implements AuditSink {
  private final MongoOutbox outbox;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MongoOutboxAuditSink(MongoOutbox outbox, TransactionTemplate tx, Clock clock) {
    this.outbox = outbox;
    this.tx = new TransactionTemplate(tx.getTransactionManager());
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  @Override
  public void emit(AuditRecord record) {
    MongoMessaging.inTransaction(
        tx,
        () -> {
          outbox.add(record.toCloudEvent(clock));
          return null;
        });
  }
}
