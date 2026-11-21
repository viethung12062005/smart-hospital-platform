package org.shp.audit.app;

import java.util.List;
import org.shp.audit.infra.AuditSpool;
import org.shp.events.CloudEvent;
import org.shp.messaging.PoisonEventException;

/**
 * Takes one validated {@code shp.audit.recorded.v1} event and puts its record in the spool. Runs
 * inside the inbox transaction (see {@code AuditConsumerConfig}), so the inbox marker and the
 * spooled record are committed together or not at all.
 */
public class AuditIntake {

  public static final String TYPE = "shp.audit.recorded.v1";
  public static final String CONSUMER = "audit";

  private final AuditSpool spool;
  private final String instance;

  public AuditIntake(AuditSpool spool, String instance) {
    this.spool = spool;
    this.instance = instance;
  }

  public void onRecorded(CloudEvent event) {
    if (event.data() == null || !event.id().equals(event.data().path("eventId").asString(""))) {
      // 05 §1.1: record.eventId = CloudEvents id, the key for deduplication.
      throw new PoisonEventException(event.id(), event.type(), List.of("data /eventId"));
    }
    spool.add(event.id(), instance, event.data().toString());
  }
}
