package org.shp.scheduling.app;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.shp.data.postgres.PostgresOutbox;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every 5 minutes marks {@code booked} appointments whose end is more than 30 minutes in the past
 * as {@code noshow} (02-db-scheduling §3.3) and emits {@code appointment-status-changed.v1} (E5).
 * Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so replicas running at the same time never
 * process the same appointment. Each batch is one transaction: status, version, history and outbox
 * event commit together.
 */
@Component
public class NoShowJob {

  private static final Logger LOG = LoggerFactory.getLogger(NoShowJob.class);

  public static final Duration GRACE = Duration.ofMinutes(30);
  public static final int BATCH_SIZE = 500;
  static final String EVENT_TYPE = "shp.scheduling.appointment-status-changed.v1";
  static final String CHANGED_BY = "system";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final PostgresOutbox outbox;
  private final Clock clock;
  private final boolean validateEvents;

  @Autowired
  public NoShowJob(
      JdbcClient jdbc,
      TransactionTemplate tx,
      PostgresOutbox outbox,
      Clock clock,
      @Value("${shp.events.validate-schema:false}") boolean validateEvents) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.tx = Objects.requireNonNull(tx, "tx must not be null");
    this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.validateEvents = validateEvents;
  }

  @Scheduled(cron = "${shp.scheduling.no-show.cron:0 */5 * * * *}", zone = "Asia/Ho_Chi_Minh")
  public void runScheduled() {
    run();
  }

  /** Process batches until none is left; returns the number of appointments marked no-show. */
  public int run() {
    int total = 0;
    int batch;
    do {
      Integer done = tx.execute(status -> markBatch());
      batch = done == null ? 0 : done;
      total += batch;
    } while (batch == BATCH_SIZE);
    if (total > 0) {
      LOG.info("No-show job marked {} appointments", total);
    }
    return total;
  }

  private record Due(UUID id, int version, UUID patientId) {}

  private int markBatch() {
    Instant now = clock.instant();
    List<Due> due =
        jdbc.sql(
                """
                SELECT id, version_id, patient_id
                  FROM scheduling.appointment
                 WHERE status = 'booked' AND end_at < ?
                 ORDER BY end_at
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """)
            .params(Timestamp.from(now.minus(GRACE)), BATCH_SIZE)
            .query((rs, i) ->
                new Due(
                    rs.getObject("id", UUID.class),
                    rs.getInt("version_id"),
                    rs.getObject("patient_id", UUID.class)))
            .list();
    for (Due d : due) {
      int next = d.version() + 1;
      String content =
          jdbc.sql(
                  """
                  UPDATE scheduling.appointment
                     SET status = 'noshow', version_id = ?, last_updated = ?,
                         content = content || jsonb_build_object(
                             'status', 'noshow',
                             'meta', coalesce(content -> 'meta', '{}'::jsonb)
                                 || jsonb_build_object('versionId', ?::text, 'lastUpdated', ?::text))
                   WHERE id = ?
                  RETURNING content::text
                  """)
              .params(next, Timestamp.from(now), next, now.toString(), d.id())
              .query(String.class)
              .single();
      jdbc.sql(
              """
              INSERT INTO scheduling.resource_history (
                  resource_type, id, version_id, last_updated, operation, changed_by, content
              ) VALUES ('Appointment', ?, ?, ?, 'update', ?, ?::jsonb)
              """)
          .params(d.id(), next, Timestamp.from(now), CHANGED_BY, content)
          .update();
      outbox.add(statusChangedEvent(d, next, now), validateEvents);
    }
    return due.size();
  }

  private CloudEvent statusChangedEvent(Due d, int version, Instant changedAt) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("appointmentId", d.id().toString());
    data.put("version", version);
    data.put("patientId", d.patientId().toString());
    data.put("previousStatus", "booked");
    data.put("newStatus", "noshow");
    data.put("changedAt", changedAt.toString());
    return CloudEvents.create("scheduling", EVENT_TYPE, "Appointment/" + d.id(), data, clock, null);
  }
}
