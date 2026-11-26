package org.shp.audit.infra;

import java.util.List;

/**
 * The query copy of the audit records (05-storage-audit-blob §1.3): Log Analytics in the cloud,
 * Loki locally. A sink either takes the whole batch or throws; the caller keeps the records and
 * calls again, so a sink must tolerate the same record arriving twice (the record carries its
 * {@code eventId}).
 */
public interface AuditSink {

  /** Sends the record lines (compact JSON, one record each); throws if any could not be sent. */
  void ship(List<String> recordLines);
}
