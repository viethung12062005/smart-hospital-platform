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
 * SCH-01 (US-009, US-011): Kiểm tra migration V001 chạy sạch trên Postgres 16 thật và bảo đảm các
 * ràng buộc nghiệp vụ, đặc biệt là exclusion constraint chống slot chồng lấn.
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
    // Đặt search_path cho connection test để truy vấn trực tiếp bảng trong scheduling
    jdbc.sql("SET search_path TO scheduling, public").update();
  }

  @Test
  @DisplayName(
      "Migration V001 tạo thành công extension btree_gist và các bảng schedule, schedule_template, slot")
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
      assertThat(tableExists).as("Bảng scheduling.%s phải tồn tại", table).isTrue();
    }
  }

  @Nested
  @DisplayName("Kiểm tra bảng schedule")
  class ScheduleTests {

    @Test
    @DisplayName("Thêm schedule hợp lệ thành công")
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
    @DisplayName("horizon_end < horizon_start vi phạm ck_schedule_horizon")
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
        "Mỗi bác sĩ tối đa 1 schedule active cho mỗi chuyên khoa (ux_schedule_active_practitioner_specialty)")
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

      // Thêm schedule active thứ 2 cùng bác sĩ + chuyên khoa phải thất bại
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

      // Thêm schedule không active (active=false) cùng bác sĩ + chuyên khoa vẫn được phép
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
  @DisplayName("Kiểm tra bảng schedule_template")
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
    @DisplayName("Thêm template hợp lệ và kiểm tra các ràng buộc weekday, slot_minutes, thời gian")
    void templateValidations() {
      UUID scheduleId = createSchedule();

      // Hợp lệ: thứ Hai (1), 08:00 - 11:30, slot 15 phút
      jdbc.sql(
              """
              INSERT INTO scheduling.schedule_template (id, schedule_id, weekday, start_time, end_time, slot_minutes, valid_from, created_at, created_by)
              VALUES (?, ?, 1, '08:00', '11:30', 15, '2026-11-01', now(), 'nurse1')
              """)
          .params(UUID.randomUUID(), scheduleId)
          .update();

      // Vi phạm weekday (8 > 7)
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

      // Vi phạm slot_minutes (25 không nằm trong (10, 15, 20, 30))
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

      // Vi phạm end_time <= start_time (ck_template_time)
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

      // Vi phạm valid_to < valid_from (ck_template_valid)
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
  @DisplayName("Kiểm tra bảng slot và ràng buộc chống chồng lấn (ex_slot_no_overlap)")
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
    @DisplayName("Ràng buộc trạng thái và khoảng thời gian của slot")
    void slotStatusAndPeriodChecks() {
      UUID practitionerId = UUID.randomUUID();
      UUID scheduleId = createSchedule(practitionerId, "dermatology");

      // Trạng thái không hợp lệ
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

      // end_at <= start_at vi phạm ck_slot_period
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
        "Hai slot liền kề nhau trong cùng một schedule được phép lưu (ranh giới chạm nhau)")
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

      // Slot 2: [08:15, 08:30) — chạm ranh giới 08:15 nhưng không chồng lấn
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
    @DisplayName("Hai slot chồng lấn trong cùng một schedule bị chặn bởi ex_slot_no_overlap")
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

      // Slot 2: [09:15, 09:45) chồng lấn với Slot 1 tại [09:15, 09:30)
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
    @DisplayName("Hai slot cùng thời gian nhưng ở hai schedule khác nhau được phép tồn tại")
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

      // Cùng khoảng giờ nhưng schedule khác
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
