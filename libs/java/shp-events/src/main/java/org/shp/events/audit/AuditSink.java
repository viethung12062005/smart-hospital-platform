package org.shp.events.audit;

/**
 * Destination for audit records. Production: write to the outbox in the same transaction (no audit
 * loss when the bus fails); test: {@link InMemoryAuditSink}.
 */
public interface AuditSink {
  void emit(AuditRecord record);
}
