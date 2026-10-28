package org.shp.events.audit;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Sink trong bộ nhớ cho test và chạy local nhanh. */
public final class InMemoryAuditSink implements AuditSink {
  private final ConcurrentLinkedQueue<AuditRecord> records = new ConcurrentLinkedQueue<>();

  @Override
  public void emit(AuditRecord record) {
    records.add(record);
  }

  public List<AuditRecord> records() {
    return List.copyOf(records);
  }

  public void clear() {
    records.clear();
  }
}
