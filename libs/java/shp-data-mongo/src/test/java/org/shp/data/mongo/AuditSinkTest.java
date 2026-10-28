package org.shp.data.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.AuditRecordBuilder;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

class AuditSinkTest extends MongoTestBase {
  @Test
  void auditIsStoredDurablyAsAnE11OutboxEvent() {
    var sink = new MongoOutboxAuditSink(new MongoOutbox(DB), TX, Clock.systemUTC());
    sink.emit(
        AuditRecordBuilder.build(
            Clock.systemUTC(),
            "clinical",
            "read",
            "success",
            new AuditRecord.Actor("s", "nurse", null),
            "Patient",
            null,
            "TREAT",
            null,
            null,
            null,
            null));
    assertThat(
            DB.count(
                Query.query(Criteria.where("eventType").is("shp.audit.recorded.v1")),
                MongoMessaging.OUTBOX))
        .isEqualTo(1);
  }
}
