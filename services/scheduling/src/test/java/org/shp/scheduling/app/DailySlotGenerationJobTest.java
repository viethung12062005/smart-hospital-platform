package org.shp.scheduling.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.hl7.fhir.r4.model.Schedule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.DailySlotGenerationJob.RunResult;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.scheduling.domain.ScheduleTemplateRecord;
import org.shp.scheduling.infra.ScheduleRepository;
import org.shp.scheduling.infra.ScheduleTemplateRepository;
import org.shp.scheduling.infra.SlotRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-08 (US-011): Integration tests for {@link DailySlotGenerationJob} on PostgreSQL 16. Verifies
 * advisory-lock coordination across two concurrent replicas, horizon extension to today + 30 days
 * in Asia/Ho_Chi_Minh with resource_history updates, and skipping inactive schedules.
 */
@Testcontainers
class DailySlotGenerationJobTest {

  @Container
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  // 2026-11-07 01:00:00+07:00 = 2026-11-06 18:00:00Z; today (VN) = 2026-11-07, today+30 =
  // 2026-12-07 (Monday).
  private static final Instant FIXED_NOW = Instant.parse("2026-11-06T18:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  private static SimpleDriverDataSource dataSource;
  private static JdbcClient jdbc;
  private static ScheduleRepository scheduleRepository;
  private static ScheduleTemplateRepository templateRepository;
  private static SlotRepository slotRepository;

  @BeforeAll
  static void setup() {
    dataSource =
        new SimpleDriverDataSource(
            new org.postgresql.Driver(), DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration", "classpath:db/shp-messaging/postgres")
        .load()
        .migrate();

    jdbc = JdbcClient.create(dataSource);
    scheduleRepository = new ScheduleRepository(jdbc, FIXED_CLOCK);
    templateRepository = new ScheduleTemplateRepository(jdbc, FIXED_CLOCK);
    slotRepository = new SlotRepository(jdbc);
  }

  @BeforeEach
  void cleanTables() {
    jdbc.sql(
            "TRUNCATE scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history CASCADE")
        .update();
  }

  private static DailySlotGenerationJob newReplicaJob() {
    JdbcClient replicaJdbc = JdbcClient.create(dataSource);
    TransactionTemplate replicaTx =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    ScheduleRepository replicaSchedules = new ScheduleRepository(replicaJdbc, FIXED_CLOCK);
    ScheduleTemplateRepository replicaTemplates =
        new ScheduleTemplateRepository(replicaJdbc, FIXED_CLOCK);
    SlotRepository replicaSlots = new SlotRepository(replicaJdbc);
    SlotGenerator replicaGenerator =
        new SlotGenerator(replicaSchedules, replicaTemplates, replicaSlots, FIXED_CLOCK);
    return new DailySlotGenerationJob(
        replicaJdbc, replicaTx, replicaSchedules, replicaGenerator, FIXED_CLOCK);
  }

  private ScheduleRecord seedSchedule(
      boolean active, LocalDate horizonStart, LocalDate horizonEnd) {
    UUID scheduleId = UUID.randomUUID();
    var schedule =
        new ScheduleRecord(
            scheduleId,
            1,
            FIXED_NOW,
            active,
            UUID.randomUUID(),
            "cardiology",
            horizonStart,
            horizonEnd,
            "{\"resourceType\":\"Schedule\",\"id\":\"" + scheduleId + "\"}");
    ScheduleRecord created = scheduleRepository.create(schedule, "nurse-1");

    templateRepository.replaceAll(
        scheduleId,
        List.of(
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                scheduleId,
                1, // Monday -> matches 2026-12-07
                LocalTime.of(8, 0),
                LocalTime.of(11, 30),
                15,
                LocalDate.of(2026, 11, 1),
                null,
                FIXED_NOW,
                "nurse-1")),
        "nurse-1");
    return created;
  }

  @Test
  @DisplayName("Two replicas running at once acquire advisory lock cleanly: only one executes")
  void twoReplicasAtOnceOnlyOneExecutes() throws Exception {
    ScheduleRecord schedule =
        seedSchedule(true, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 12, 6));

    DailySlotGenerationJob replica1 = newReplicaJob();
    DailySlotGenerationJob replica2 = newReplicaJob();

    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch releaseReplica1 = new CountDownLatch(1);

    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<RunResult> future1 =
          executor.submit(() ->
              replica1.runDailyGeneration(() -> {
                lockHeld.countDown();
                try {
                  if (!releaseReplica1.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release replica1");
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
              }));

      assertThat(lockHeld.await(15, TimeUnit.SECONDS)).isTrue();

      RunResult result2 = replica2.runDailyGeneration();
      assertThat(result2.executed()).isFalse();
      assertThat(result2.schedulesProcessed()).isZero();
      assertThat(result2.slotsCreated()).isZero();
      assertThat(result2.slotsSkipped()).isZero();

      releaseReplica1.countDown();
      RunResult result1 = future1.get(15, TimeUnit.SECONDS);
      assertThat(result1.executed()).isTrue();
      assertThat(result1.schedulesProcessed()).isEqualTo(1);
      assertThat(result1.slotsCreated()).isEqualTo(14);
      assertThat(result1.slotsSkipped()).isZero();
    }

    assertThat(slotRepository.findByScheduleId(schedule.id())).hasSize(14);
    assertThat(scheduleRepository.findById(schedule.id()).orElseThrow().horizonEnd())
        .isEqualTo(LocalDate.of(2026, 12, 7));
  }

  @Test
  @DisplayName("Extends schedule horizon from horizon_end + 1 to today + 30 and records history")
  void extendsHorizonFromHorizonEndPlusOneToTodayPlus30AndUpdatesHistory() {
    ScheduleRecord schedule =
        seedSchedule(true, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 6));
    DailySlotGenerationJob job = newReplicaJob();

    RunResult firstRun = job.runDailyGeneration();
    assertThat(firstRun.executed()).isTrue();
    assertThat(firstRun.schedulesProcessed()).isEqualTo(1);
    assertThat(firstRun.slotsCreated()).isEqualTo(14);
    assertThat(firstRun.slotsSkipped()).isZero();

    assertThat(slotRepository.findByScheduleId(schedule.id())).hasSize(14);

    ScheduleRecord updated = scheduleRepository.findById(schedule.id()).orElseThrow();
    assertThat(updated.horizonStart()).isEqualTo(LocalDate.of(2026, 11, 7));
    assertThat(updated.horizonEnd()).isEqualTo(LocalDate.of(2026, 12, 7));
    assertThat(updated.versionId()).isEqualTo(2);

    Schedule parsedFhir = FhirJson.parse(updated.content(), Schedule.class);
    assertThat(parsedFhir.getMeta().getVersionId()).isEqualTo("2");
    assertThat(parsedFhir.hasPlanningHorizon()).isTrue();
    assertThat(parsedFhir.getPlanningHorizon().getStart())
        .isEqualTo(java.util.Date.from(Instant.parse("2026-11-06T17:00:00Z")));
    assertThat(parsedFhir.getPlanningHorizon().getEnd())
        .isEqualTo(java.util.Date.from(Instant.parse("2026-12-07T16:59:59Z")));

    var history = scheduleRepository.findHistory(schedule.id());
    assertThat(history).hasSize(2);
    assertThat(history.get(1).versionId()).isEqualTo(2);
    assertThat(history.get(1).operation()).isEqualTo("update");
    assertThat(history.get(1).changedBy()).isEqualTo("system");

    // Re-running on the same day sees no active schedule with horizon_end < today + 30.
    RunResult secondRun = job.runDailyGeneration();
    assertThat(secondRun.executed()).isTrue();
    assertThat(secondRun.schedulesProcessed()).isZero();
    assertThat(secondRun.slotsCreated()).isZero();
    assertThat(slotRepository.findByScheduleId(schedule.id())).hasSize(14);
  }

  @Test
  @DisplayName("Inactive schedules are skipped without generating slots or extending horizon")
  void skipsInactiveSchedules() {
    ScheduleRecord inactive =
        seedSchedule(false, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 6));
    DailySlotGenerationJob job = newReplicaJob();

    RunResult result = job.runDailyGeneration();
    assertThat(result.executed()).isTrue();
    assertThat(result.schedulesProcessed()).isZero();
    assertThat(result.slotsCreated()).isZero();
    assertThat(result.slotsSkipped()).isZero();

    ScheduleRecord reloaded = scheduleRepository.findById(inactive.id()).orElseThrow();
    assertThat(reloaded.horizonEnd()).isEqualTo(LocalDate.of(2026, 12, 6));
    assertThat(reloaded.versionId()).isEqualTo(1);
    assertThat(slotRepository.findByScheduleId(inactive.id())).isEmpty();
  }
}
