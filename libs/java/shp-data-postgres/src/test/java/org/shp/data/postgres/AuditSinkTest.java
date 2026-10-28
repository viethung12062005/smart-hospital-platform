package org.shp.data.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.AuditRecordBuilder;

class AuditSinkTest extends PostgresTestBase {
  @Test
  void auditSurvivesARolledBackBusinessTransaction() {
    var sink = new PostgresOutboxAuditSink(new PostgresOutbox(JDBC), TX, Clock.systemUTC());
    var rec =
        AuditRecordBuilder.build(
            Clock.systemUTC(),
            "scheduling",
            "read",
            "denied",
            new AuditRecord.Actor("s", "patient", null),
            "Appointment",
            null,
            null,
            "unmet-own",
            null,
            null,
            null);
    TX.executeWithoutResult(
        s -> {
          insertThing("business");
          sink.emit(rec);
          s.setRollbackOnly();
        });
    assertThat(count("SELECT count(*) FROM thing")).isZero();
    assertThat(count("SELECT count(*) FROM outbox WHERE event_type = 'shp.audit.recorded.v1'"))
        .isEqualTo(1);
  }
}
