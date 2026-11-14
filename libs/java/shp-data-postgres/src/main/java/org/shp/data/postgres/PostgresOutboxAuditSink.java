package org.shp.data.postgres;

import java.time.Clock;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.AuditSink;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write the audit record as an E11 event to the outbox in its OWN transaction (REQUIRES_NEW): the
 * audit of one access is still stored even when the business transaction rolls back, and is not
 * lost when the bus fails (the relay publishes it later).
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
