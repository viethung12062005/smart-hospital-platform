package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Schedule;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.SlotGenerator.SlotGenerationResult;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.scheduling.infra.ScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily 01:00 (Asia/Ho_Chi_Minh) job that extends active schedules up to {@code today + 30} days
 * and generates missing examination slots from weekly templates. Coordinated across replicas with a
 * transaction-scoped PostgreSQL advisory lock so only one replica executes per run.
 */
@Component
public class DailySlotGenerationJob {

  private static final Logger LOG = LoggerFactory.getLogger(DailySlotGenerationJob.class);

  public static final String LOCK_KEY = "scheduling:daily-slot-generation";
  public static final int HORIZON_DAYS = 30;

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final ScheduleRepository scheduleRepository;
  private final SlotGenerator slotGenerator;
  private final Clock clock;

  @Autowired
  public DailySlotGenerationJob(
      JdbcClient jdbc,
      TransactionTemplate tx,
      ScheduleRepository scheduleRepository,
      SlotGenerator slotGenerator,
      Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.tx = Objects.requireNonNull(tx, "tx must not be null");
    this.scheduleRepository =
        Objects.requireNonNull(scheduleRepository, "scheduleRepository must not be null");
    this.slotGenerator = Objects.requireNonNull(slotGenerator, "slotGenerator must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public record RunResult(
      boolean executed, int schedulesProcessed, int slotsCreated, int slotsSkipped) {}

  @Scheduled(cron = "${shp.scheduling.slot-generation.cron:0 0 1 * * *}", zone = "Asia/Ho_Chi_Minh")
  public void runScheduled() {
    runDailyGeneration();
  }

  /** Run daily slot generation once, skipping if another replica holds the advisory lock. */
  public RunResult runDailyGeneration() {
    return runDailyGeneration(() -> {});
  }

  RunResult runDailyGeneration(Runnable onLockAcquired) {
    Objects.requireNonNull(onLockAcquired, "onLockAcquired must not be null");
    RunResult result =
        tx.execute(status -> {
          Boolean acquired =
              jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")
                  .param(LOCK_KEY)
                  .query(Boolean.class)
                  .single();
          if (!Boolean.TRUE.equals(acquired)) {
            return new RunResult(false, 0, 0, 0);
          }

          onLockAcquired.run();

          Instant now = clock.instant();
          LocalDate today = LocalDate.ofInstant(now, SlotGenerator.VN_ZONE);
          LocalDate targetEnd = today.plusDays(HORIZON_DAYS);

          List<ScheduleRecord> schedules = scheduleRepository.findActiveBeforeHorizonEnd(targetEnd);

          int schedulesProcessed = 0;
          int slotsCreated = 0;
          int slotsSkipped = 0;

          for (ScheduleRecord schedule : schedules) {
            LocalDate from = schedule.horizonEnd().plusDays(1);
            if (from.isBefore(today)) {
              from = today;
            }

            SlotGenerationResult gen = slotGenerator.generate(schedule.id(), from, targetEnd);
            slotsCreated += gen.created();
            slotsSkipped += gen.skippedExisting();

            int nextVersion = schedule.versionId() + 1;
            Schedule fhirSchedule = FhirJson.parse(schedule.content(), Schedule.class);
            Period horizon = new Period();
            horizon.setStart(Date.from(today.atStartOfDay(SlotGenerator.VN_ZONE).toInstant()));
            horizon.setEnd(
                Date.from(targetEnd.atTime(23, 59, 59).atZone(SlotGenerator.VN_ZONE).toInstant()));
            fhirSchedule.setPlanningHorizon(horizon);
            fhirSchedule
                .getMeta()
                .setVersionId(String.valueOf(nextVersion))
                .setLastUpdated(Date.from(now));

            ScheduleRecord updatedRecord =
                new ScheduleRecord(
                    schedule.id(),
                    nextVersion,
                    now,
                    schedule.active(),
                    schedule.practitionerId(),
                    schedule.specialty(),
                    today,
                    targetEnd,
                    FhirJson.serialize(fhirSchedule));

            scheduleRepository.update(updatedRecord, schedule.versionId(), "system");
            schedulesProcessed++;
          }

          return new RunResult(true, schedulesProcessed, slotsCreated, slotsSkipped);
        });
    if (result == null) {
      throw new IllegalStateException("Daily slot generation transaction returned null result");
    }
    if (result.executed()) {
      LOG.info(
          "Daily slot generation finished: schedules={}, created={}, skipped={}",
          result.schedulesProcessed(),
          result.slotsCreated(),
          result.slotsSkipped());
    }
    return result;
  }
}
