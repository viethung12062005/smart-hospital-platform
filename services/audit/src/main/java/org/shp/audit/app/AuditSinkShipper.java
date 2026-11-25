package org.shp.audit.app;

import java.util.List;
import org.bson.Document;
import org.shp.audit.infra.AuditSink;
import org.shp.audit.infra.AuditSpool;

/**
 * Sends spooled records to the query sink (05-storage-audit-blob §1.3) every poll interval, so a
 * record is queryable within seconds, well inside the 1-minute target of NFR-SEC5. A record is
 * marked shipped only after the sink accepted it; on a sink error nothing is marked and the next
 * call sends the same records again. The batch flusher calls {@link #shipPending} before it removes
 * records from the spool, so a sink outage can never lose a record.
 */
public class AuditSinkShipper {

  private final AuditSpool spool;
  private final AuditSink sink;
  private final String instance;
  private final int maxRecords;

  public AuditSinkShipper(AuditSpool spool, AuditSink sink, String instance, int maxRecords) {
    this.spool = spool;
    this.sink = sink;
    this.instance = instance;
    this.maxRecords = maxRecords;
  }

  /** Ships the next unshipped records of this instance; returns how many were sent. */
  public synchronized int shipDue() {
    return send(spool.unshipped(instance, maxRecords));
  }

  /** Ships whichever of the given records are not shipped yet (before they are removed). */
  public synchronized int shipPending(List<String> ids) {
    return send(spool.byIds(ids).stream().filter(d -> !d.getBoolean("shipped", false)).toList());
  }

  private int send(List<Document> docs) {
    if (docs.isEmpty()) {
      return 0;
    }
    sink.ship(docs.stream().map(d -> d.getString("line")).toList());
    spool.markShipped(docs.stream().map(d -> d.getString("_id")).toList());
    return docs.size();
  }
}
