package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.shp.data.postgres.PostgresOutbox;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.scheduling.domain.AppointmentFhir;
import org.shp.scheduling.domain.AppointmentRecord;
import org.shp.scheduling.infra.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled job (every 5 minutes) that transitions {@code booked} appointments past {@code end + 30
 * min} to {@code noshow}, writes {@code resource_history}, and emits {@code
 * shp.scheduling.appointment-status-changed.v1} (E5). Uses {@code FOR UPDATE SKIP LOCKED} so
 * multiple scheduling replicas do not double-process overdue rows (02 §3.3).
 */
@Component
public class NoShowMarkingJob {
  private static final Logger LOG = LoggerFactory.getLogger(NoShowMarkingJob.class);

  public static final Duration GRACE_PERIOD = Duration.ofMinutes(30);
  public static final int DEFAULT_BATCH_SIZE = 500;
  static final String EVENT_TYPE = "shp.scheduling.appointment-status-changed.v1";
  static final String CHANGED_BY = "system/no-show-job";

  private final AppointmentRepository appointments;
  private final PostgresOutbox outbox;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean validateEvents;

  @Autowired
  public NoShowMarkingJob(
      AppointmentRepository appointments,
      PostgresOutbox outbox,
      TransactionTemplate tx,
      Clock clock,
      @Value("${shp.events.validate-schema:false}") boolean validateEvents) {
    this.appointments = Objects.requireNonNull(appointments, "appointments must not be null");
    this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    this.tx = Objects.requireNonNull(tx, "tx must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.validateEvents = validateEvents;
  }

  @Scheduled(cron = "${shp.scheduling.no-show.cron:0 */5 * * * *}")
  public void runScheduled() {
    markOverdueNoShows();
  }

  /** Processes all overdue {@code booked} appointments in batches until drained. */
  public int markOverdueNoShows() {
    int total = 0;
    int batch;
    do {
      batch = markOverdueBatch(DEFAULT_BATCH_SIZE, () -> {});
      total += batch;
    } while (batch == DEFAULT_BATCH_SIZE);
    if (total > 0) {
      LOG.info("Marked {} overdue appointments as noshow", total);
    }
    return total;
  }

  int markOverdueBatch(int batchSize, Runnable onRowsLocked) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    Objects.requireNonNull(onRowsLocked, "onRowsLocked must not be null");
    Integer processed =
        tx.execute(status -> {
          Instant now = clock.instant();
          Instant cutoff = now.minus(GRACE_PERIOD);
          List<AppointmentRecord> overdue = appointments.lockOverdueBooked(cutoff, batchSize);
          if (overdue.isEmpty()) {
            return 0;
          }
          onRowsLocked.run();
          for (AppointmentRecord current : overdue) {
            int newVersion = current.versionId() + 1;
            String newContent = AppointmentFhir.noshow(current.content(), newVersion, now);
            AppointmentRecord updated =
                appointments.markNoShow(current, CHANGED_BY, now, newContent);
            outbox.add(statusChangedEvent(updated, now), validateEvents);
          }
          return overdue.size();
        });
    if (processed == null) {
      throw new IllegalStateException("No-show marking transaction returned null result");
    }
    return processed;
  }

  private CloudEvent statusChangedEvent(AppointmentRecord a, Instant changedAt) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("appointmentId", a.id().toString());
    data.put("version", a.versionId());
    data.put("patientId", a.patientId().toString());
    data.put("previousStatus", "booked");
    data.put("newStatus", "noshow");
    data.put("changedAt", changedAt.toString());
    return CloudEvents.create("scheduling", EVENT_TYPE, "Appointment/" + a.id(), data, clock, null);
  }
}
