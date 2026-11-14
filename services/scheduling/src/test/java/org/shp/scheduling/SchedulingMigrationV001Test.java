package org.shp.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-01 (US-009, US-011): Verify that migration V001 runs cleanly on a real Postgres 16 and assert
 * the business constraints, especially the exclusion constraint that prevents overlapping slots.
 */
@Testcontainers
class SchedulingMigrationV001Test {

  @Container
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  static JdbcClient jdbc;

  @BeforeAll
  static void runMigrationsAndInitClient() {
    var ds =
        new SimpleDriverDataSource(
            new org.postgresql.Driver(), DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());

    var flyway =
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration", "classpath:db/shp-messaging/postgres")
            .load();
    var result = flyway.migrate();

    assertThat(result.success).isTrue();
    assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(2);

    for (var info : flyway.info().all()) {
      assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS);
    }

    jdbc = JdbcClient.create(ds);
    // Set search_path for the test connection so we can query tables in scheduling directly
    jdbc.sql("SET search_path TO scheduling, public").update();
  }

  @Test
  @DisplayName(
      "Migration V001 successfully creates the btree_gist extension and the schedule, schedule_template, slot tables")
  void tablesAndExtensionExist() {
    Boolean extensionInstalled =
        jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'btree_gist')")
            .query(Boolean.class)
            .single();
    assertThat(extensionInstalled).isTrue();

    for (String table : new String[] {"schedule", "schedule_template", "slot"}) {
      Boolean tableExists =
          jdbc.sql(
                  "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema = 'scheduling' AND table_name = ?)")
              .param(table)
              .query(Boolean.class)
              .single();
      assertThat(tableExists).as("Table scheduling.%s must exist", table).isTrue();
    }
  }

  @Nested
  @DisplayName("Check the schedule table")
  class ScheduleTests {

    @Test
    @DisplayName("Inserting a valid schedule succeeds")
    void insertValidSchedule() {
      UUID id = UUID.randomUUID();
      UUID practitionerId = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO scheduling.schedule (id, version_id, last_updated, active, practitioner_id, specialty, horizon_start, horizon_end, content)
              VALUES (?, 1, now(), true, ?, 'cardiology', '2026-11-01', '2026-11-30', '{"resourceType":"Schedule"}'::jsonb)
              """)
          .params(id, practitionerId)
          .update();

      Long count =
          jdbc.sql("SELECT count(*) FROM scheduling.schedule WHERE id = ?")
              .param(id)
              .query(Long.class)
              .single();
      assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("horizon_end < horizon_start violates ck_schedule_horizon")
    void horizonCheckConstraint() {
      UUID id = UUID.randomUUID();
      UUID practitionerId = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  jdbc.sql(
                          """
                          INSERT INTO scheduling.schedule (id, version_id, last_updated, active, practitioner_id, specialty, horizon_start, horizon_end, content)
                          VALUES (?, 1, now(), false, ?, 'pediatrics', '2026-11-30', '2026-11-01', '{}'::jsonb)
                          """)
                      .params(id, practitionerId)
                      .update())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_schedule_horizon");
    }

    @Test
    @DisplayName(
        "Each doctor has at most 1 active schedule per specialty (ux_schedule_active_practitioner_specialty)")
    void activeScheduleUniqueConstraint() {
      UUID practitionerId = UUID.randomUUID();
      String specialty = "neurology";

      jdbc.sql(
              """
              INSERT INTO scheduling.schedule (id, version_id, last_updated, active, practitioner_id, specialty, horizon_start, horizon_end, content)
              VALUES (?, 1, now(), true, ?, ?, '2026-11-01', '2026-11-30', '{}'::jsonb)
              """)
          .params(UUID.randomUUID(), practitionerId, specialty)
          .update();

      // Adding a 2nd active schedule with the same doctor + specialty must fail
      assertThatThrownBy(
              () ->
                  jdbc.sql(
                          """
                          INSERT INTO scheduling.schedule (id, version_id, last_updated, active, practitioner_id, specialty, horizon_start, horizon_end, content)
                          VALUES (?, 1, now(), true, ?, ?, '2026-12-01', '2026-12-31', '{}'::jsonb)
                          """)
                      .params(UUID.randomUUID(), practitionerId, specialty)
                      .update())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ux_schedule_active_practitioner_specialty");

      // Adding a non-active schedule (active=false) with the same doctor + specialty is still
      // allowed
      jdbc.sql(
              """
              INSERT INTO scheduling.schedule (id, version_id, last_updated, active, practitioner_id, specialty, horizon_start, horizon_end, content)
              VALUES (?, 1, now(), false, ?, ?, '2026-12-01', '2026-12-31', '{}'::jsonb)
              """)
          .params(UUID.randomUUID(), practitionerId, specialty)
          .update();
    }
  }

  @Nested
  @DisplayName("Check the schedule_template table")
  class ScheduleTemplateTests {

    private UUID createSchedule() {
      UUID id = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO scheduling.schedule (id, version_id, last_updated, active, practitioner_id, specialty, horizon_start, horizon_end, content)
              VALUES (?, 1, now(), false, ?, 'general', '2026-11-01', '2026-11-30', '{}'::jsonb)
              """)
          .params(id, UUID.randomUUID())
          .update();
      return id;
    }

    @Test
    @DisplayName("Add a valid template and check the weekday, slot_minutes, and time constraints")
    void templateValidations() {
      UUID scheduleId = createSchedule();

      // Valid: Monday (1), 08:00 - 11:30, 15-minute slot
      jdbc.sql(
              """
              INSERT INTO scheduling.schedule_template (id, schedule_id, weekday, start_time, end_time, slot_minutes, valid_from, created_at, created_by)
              VALUES (?, ?, 1, '08:00', '11:30', 15, '2026-11-01', now(), 'nurse1')
              """)
          .params(UUID.randomUUID(), scheduleId)
          .update();

      // weekday violation (8 > 7)
      assertThatThrownBy(
              () ->
                  jdbc.sql(
                          """
                          INSERT INTO scheduling.schedule_template (id, schedule_id, weekday, start_time, end_time, slot_minutes, valid_from, created_at, created_by)
                          VALUES (?, ?, 8, '08:00', '11:30', 15, '2026-11-01', now(), 'nurse1')
                          """)
                      .params(UUID.randomUUID(), scheduleId)
                      .update())
          .isInstanceOf(DataIntegrityViolationException.class);

      // slot_minutes violation (25 is not in (10, 15, 20, 30))
      assertThatThrownBy(
              () ->
                  jdbc.sql(
                          """
                          INSERT INTO scheduling.schedule_template (id, schedule_id, weekday, start_time, end_time, slot_minutes, valid_from, created_at, created_by)
                          VALUES (?, ?, 2, '08:00', '11:30', 25, '2026-11-01', now(), 'nurse1')
                          """)
                      .params(UUID.randomUUID(), scheduleId)
                      .update())
          .isInstanceOf(DataIntegrityViolationException.class);

      // end_time <= start_time violation (ck_template_time)
      assertThatThrownBy(
              () ->
                  jdbc.sql(
                          """
                          INSERT INTO scheduling.schedule_template (id, schedule_id, weekday, start_time, end_time, slot_minutes, valid_from, created_at, created_by)
                          VALUES (?, ?, 3, '11:30', '08:00', 15, '2026-11-01', now(), 'nurse1')
                          """)
                      .params(UUID.randomUUID(), scheduleId)
                      .update())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_template_time");

      // valid_to < valid_from violation (ck_template_valid)
      assertThatThrownBy(
              () ->
                  jdbc.sql(
                          """
                          INSERT INTO scheduling.schedule_template (id, schedule_id, weekday, start_time, end_time, slot_minutes, valid_from, valid_to, created_at, created_by)
                          VALUES (?, ?, 4, '08:00', '11:30', 15, '2026-11-30', '2026-11-01', now(), 'nurse1')
                          """)
                      .params(UUID.randomUUID(), scheduleId)
                      .update())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_template_valid");
    }
  }

  @Nested
  @DisplayName("Check the slot table and the overlap-prevention constraint (ex_slot_no_overlap)")
  class SlotTests {

    private UUID createSchedule(UUID practitionerId, String specialty) {
      UUID id = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO scheduling.schedule (id, version_id, last_updated, active, practitioner_id, specialty, horizon_start, horizon_end, content)
              VALUES (?, 1, now(), false, ?, ?, '2026-11-01', '2026-11-30', '{}'::jsonb)
              """)
          .params(id, practitionerId, specialty)
          .update();
      return id;
    }

    @Test
    @DisplayName("Slot status and time range constraints")
    void slotStatusAndPeriodChecks() {
      UUID practitionerId = UUID.randomUUID();
      UUID scheduleId = createSchedule(practitionerId, "dermatology");

      // Invalid status
      assertThatThrownBy(
              () ->
                  jdbc.sql(
                          """
                          INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content)
                          VALUES (?, 1, now(), ?, ?, 'dermatology', 'invalid-status', now(), now() + interval '15 minutes', '{}'::jsonb)
                          """)
                      .params(UUID.randomUUID(), scheduleId, practitionerId)
                      .update())
          .isInstanceOf(DataIntegrityViolationException.class);

      // end_at <= start_at violates ck_slot_period
      OffsetDateTime now = OffsetDateTime.parse("2026-11-20T08:00:00Z");
      assertThatThrownBy(
              () ->
                  jdbc.sql(
                          """
                          INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content)
                          VALUES (?, 1, now(), ?, ?, 'dermatology', 'free', ?, ?, '{}'::jsonb)
                          """)
                      .params(UUID.randomUUID(), scheduleId, practitionerId, now, now)
                      .update())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_slot_period");
    }

    @Test
    @DisplayName(
        "Two adjacent slots in the same schedule are allowed to be stored (boundaries touch)")
    void adjacentSlotsAreAllowed() {
      UUID practitionerId = UUID.randomUUID();
      UUID scheduleId = createSchedule(practitionerId, "orthopedics");

      OffsetDateTime start1 = OffsetDateTime.parse("2026-11-20T08:00:00Z");
      OffsetDateTime end1 = OffsetDateTime.parse("2026-11-20T08:15:00Z");
      OffsetDateTime end2 = OffsetDateTime.parse("2026-11-20T08:30:00Z");

      // Slot 1: [08:00, 08:15)
      jdbc.sql(
              """
              INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content)
              VALUES (?, 1, now(), ?, ?, 'orthopedics', 'free', ?, ?, '{}'::jsonb)
              """)
          .params(UUID.randomUUID(), scheduleId, practitionerId, start1, end1)
          .update();

      // Slot 2: [08:15, 08:30) — touches the 08:15 boundary but does not overlap
      jdbc.sql(
              """
              INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content)
              VALUES (?, 1, now(), ?, ?, 'orthopedics', 'free', ?, ?, '{}'::jsonb)
              """)
          .params(UUID.randomUUID(), scheduleId, practitionerId, end1, end2)
          .update();

      Long count =
          jdbc.sql("SELECT count(*) FROM scheduling.slot WHERE schedule_id = ?")
              .param(scheduleId)
              .query(Long.class)
              .single();
      assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("Two overlapping slots in the same schedule are blocked by ex_slot_no_overlap")
    void overlappingSlotsOnSameScheduleAreRejected() {
      UUID practitionerId = UUID.randomUUID();
      UUID scheduleId = createSchedule(practitionerId, "ophthalmology");

      OffsetDateTime start1 = OffsetDateTime.parse("2026-11-20T09:00:00Z");
      OffsetDateTime end1 = OffsetDateTime.parse("2026-11-20T09:30:00Z");

      // Slot 1: [09:00, 09:30)
      jdbc.sql(
              """
              INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content)
              VALUES (?, 1, now(), ?, ?, 'ophthalmology', 'free', ?, ?, '{}'::jsonb)
              """)
          .params(UUID.randomUUID(), scheduleId, practitionerId, start1, end1)
          .update();

      // Slot 2: [09:15, 09:45) overlaps Slot 1 at [09:15, 09:30)
      OffsetDateTime start2 = OffsetDateTime.parse("2026-11-20T09:15:00Z");
      OffsetDateTime end2 = OffsetDateTime.parse("2026-11-20T09:45:00Z");

      assertThatThrownBy(
              () ->
                  jdbc.sql(
                          """
                          INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content)
                          VALUES (?, 1, now(), ?, ?, 'ophthalmology', 'free', ?, ?, '{}'::jsonb)
                          """)
                      .params(UUID.randomUUID(), scheduleId, practitionerId, start2, end2)
                      .update())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ex_slot_no_overlap");
    }

    @Test
    @DisplayName("Two slots with the same time but in two different schedules are allowed to exist")
    void overlappingTimeOnDifferentSchedulesAllowed() {
      UUID practitioner1 = UUID.randomUUID();
      UUID practitioner2 = UUID.randomUUID();
      UUID schedule1 = createSchedule(practitioner1, "internal1");
      UUID schedule2 = createSchedule(practitioner2, "internal2");

      OffsetDateTime start = OffsetDateTime.parse("2026-11-20T10:00:00Z");
      OffsetDateTime end = OffsetDateTime.parse("2026-11-20T10:30:00Z");

      jdbc.sql(
              """
              INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content)
              VALUES (?, 1, now(), ?, ?, 'internal1', 'free', ?, ?, '{}'::jsonb)
              """)
          .params(UUID.randomUUID(), schedule1, practitioner1, start, end)
          .update();

      // Same time range but a different schedule
      jdbc.sql(
              """
              INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content)
              VALUES (?, 1, now(), ?, ?, 'internal2', 'free', ?, ?, '{}'::jsonb)
              """)
          .params(UUID.randomUUID(), schedule2, practitioner2, start, end)
          .update();

      Long count =
          jdbc.sql("SELECT count(*) FROM scheduling.slot WHERE start_at = ?")
              .param(start)
              .query(Long.class)
              .single();
      assertThat(count).isEqualTo(2L);
    }
  }
}
