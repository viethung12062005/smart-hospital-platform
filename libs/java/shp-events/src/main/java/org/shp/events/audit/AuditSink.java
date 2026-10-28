package org.shp.events.audit;

/**
 * Nơi gửi bản ghi audit. Production: ghi vào outbox cùng transaction (không mất audit khi bus lỗi);
 * test: {@link InMemoryAuditSink}.
 */
public interface AuditSink {
  void emit(AuditRecord record);
}
