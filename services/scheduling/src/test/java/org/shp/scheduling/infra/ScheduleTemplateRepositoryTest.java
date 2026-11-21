package org.shp.scheduling.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.scheduling.domain.ScheduleTemplateRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-05 (US-010): Integration test for {@link ScheduleTemplateRepository} on real PostgreSQL 16.
 * Verifies atomic replacement of templates per schedule, ordering, and rollback on failure.
 */
@Testcontainers
class ScheduleTemplateRepositoryTest {

  @Container
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  private static JdbcClient jdbc;
  private static TransactionTemplate tx;
  private static ScheduleRepository scheduleRepository;
  private static ScheduleTemplateRepository templateRepository;

  private static final Instant FIXED_NOW = Instant.parse("2026-11-16T10:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  @BeforeAll
  static void setup() {
    var ds =
        new SimpleDriverDataSource(
            new org.postgresql.Driver(), DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());

    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration", "classpath:db/shp-messaging/postgres")
        .load()
        .migrate();

    jdbc = JdbcClient.create(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    scheduleRepository = new ScheduleRepository(jdbc, FIXED_CLOCK);
    templateRepository = new ScheduleTemplateRepository(jdbc, FIXED_CLOCK);
  }

  @BeforeEach
  void cleanTables() {
    jdbc.sql(
            "TRUNCATE scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history CASCADE")
        .update();
  }

  private UUID createSchedule(UUID practitionerId, String specialty) {
    UUID id = UUID.randomUUID();
    var record =
        new ScheduleRecord(
            id,
            1,
            FIXED_NOW,
            true,
            practitionerId,
            specialty,
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"resourceType\":\"Schedule\",\"id\":\"" + id + "\"}");
    scheduleRepository.create(record, "nurse-1");
    return id;
  }

  @Test
  @DisplayName(
      "replaceAll() overwrites old templates for target schedule without affecting other schedules")
  void replaceAllOverwritesOnlyTargetScheduleTemplates() {
    UUID scheduleA = createSchedule(UUID.randomUUID(), "cardiology");
    UUID scheduleB = createSchedule(UUID.randomUUID(), "pediatrics");

    var initialA =
        List.of(
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                scheduleA,
                3,
                LocalTime.of(13, 30),
                LocalTime.of(16, 30),
                15,
                LocalDate.of(2026, 12, 1),
                null,
                FIXED_NOW,
                "nurse-1"),
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                scheduleA,
                1,
                LocalTime.of(8, 0),
                LocalTime.of(11, 30),
                15,
                LocalDate.of(2026, 12, 1),
                null,
                FIXED_NOW,
                "nurse-1"));

    var initialB =
        List.of(
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                scheduleB,
                2,
                LocalTime.of(8, 0),
                LocalTime.of(12, 0),
                20,
                LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 31),
                FIXED_NOW,
                "nurse-1"));

    tx.executeWithoutResult(s -> templateRepository.replaceAll(scheduleA, initialA, "nurse-1"));
    tx.executeWithoutResult(s -> templateRepository.replaceAll(scheduleB, initialB, "nurse-1"));

    var loadedA = templateRepository.findByScheduleId(scheduleA);
    assertThat(loadedA).hasSize(2);
    assertThat(loadedA.get(0).weekday()).isEqualTo(1);
    assertThat(loadedA.get(1).weekday()).isEqualTo(3);

    // Replace A with a single empty-validTo row on weekday 5
    var replacementA =
        List.of(
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                scheduleA,
                5,
                LocalTime.of(7, 30),
                LocalTime.of(11, 30),
                30,
                LocalDate.of(2026, 12, 5),
                null,
                FIXED_NOW,
                "admin-1"));

    var savedA = tx.execute(s -> templateRepository.replaceAll(scheduleA, replacementA, "admin-1"));
    assertThat(savedA).hasSize(1);
    assertThat(savedA.get(0).weekday()).isEqualTo(5);
    assertThat(savedA.get(0).createdBy()).isEqualTo("admin-1");
    assertThat(savedA.get(0).validTo()).isNull();

    // Schedule B's templates remain untouched
    var loadedB = templateRepository.findByScheduleId(scheduleB);
    assertThat(loadedB).hasSize(1);
    assertThat(loadedB.get(0).weekday()).isEqualTo(2);
    assertThat(loadedB.get(0).validTo()).isEqualTo(LocalDate.of(2026, 12, 31));
  }

  @Test
  @DisplayName("replaceAll() with empty list clears all templates for that schedule")
  void replaceAllWithEmptyListClearsTemplates() {
    UUID scheduleId = createSchedule(UUID.randomUUID(), "cardiology");
    var initial =
        List.of(
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                scheduleId,
                1,
                LocalTime.of(8, 0),
                LocalTime.of(11, 30),
                15,
                LocalDate.of(2026, 12, 1),
                null,
                FIXED_NOW,
                "nurse-1"));

    tx.executeWithoutResult(s -> templateRepository.replaceAll(scheduleId, initial, "nurse-1"));
    assertThat(templateRepository.findByScheduleId(scheduleId)).hasSize(1);

    tx.executeWithoutResult(s -> templateRepository.replaceAll(scheduleId, List.of(), "nurse-1"));
    assertThat(templateRepository.findByScheduleId(scheduleId)).isEmpty();
  }

  @Test
  @DisplayName(
      "Transaction rollback leaves previous templates intact when a DB error occurs midway")
  void rollbackPreservesOldTemplatesOnMidwayFailure() {
    UUID scheduleId = createSchedule(UUID.randomUUID(), "cardiology");
    UUID initialTemplateId = UUID.randomUUID();

    tx.executeWithoutResult(s ->
        templateRepository.replaceAll(
            scheduleId,
            List.of(
                new ScheduleTemplateRecord(
                    initialTemplateId,
                    scheduleId,
                    1,
                    LocalTime.of(8, 0),
                    LocalTime.of(11, 30),
                    15,
                    LocalDate.of(2026, 12, 1),
                    null,
                    FIXED_NOW,
                    "nurse-1")),
            "nurse-1"));

    UUID duplicateId = UUID.randomUUID();
    var duplicatePkTemplates =
        List.of(
            new ScheduleTemplateRecord(
                duplicateId,
                scheduleId,
                2,
                LocalTime.of(8, 0),
                LocalTime.of(10, 0),
                15,
                LocalDate.of(2026, 12, 1),
                null,
                FIXED_NOW,
                "nurse-1"),
            new ScheduleTemplateRecord(
                duplicateId,
                scheduleId,
                3,
                LocalTime.of(8, 0),
                LocalTime.of(10, 0),
                15,
                LocalDate.of(2026, 12, 1),
                null,
                FIXED_NOW,
                "nurse-1"));

    assertThatThrownBy(() ->
            tx.executeWithoutResult(
                s -> templateRepository.replaceAll(scheduleId, duplicatePkTemplates, "nurse-1")))
        .isInstanceOf(DuplicateKeyException.class);

    // Old template must still exist intact
    var existing = templateRepository.findByScheduleId(scheduleId);
    assertThat(existing).hasSize(1);
    assertThat(existing.get(0).id()).isEqualTo(initialTemplateId);
    assertThat(existing.get(0).weekday()).isEqualTo(1);
  }
}
