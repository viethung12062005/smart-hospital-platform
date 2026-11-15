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
 * SCH-02 (US-015): Verify that migration V002 runs cleanly on a real Postgres 16 and enforces all
 * constraints, especially the unique partial index ux_appointment_one_active_per_slot.
 */
@Testcontainers
class SchedulingMigrationV002Test {

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
    assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(3);

    for (var info : flyway.info().all()) {
      assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS);
    }

    jdbc = JdbcClient.create(ds);
    jdbc.sql("SET search_path TO scheduling, public").update();
  }

  @Test
  @DisplayName("Migration V002 creates appointment, resource_history and messaging tables")
  void tablesExist() {
    for (String table : new String[] {"appointment", "resource_history"}) {
      Boolean tableExists =
          jdbc.sql(
                  "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema = 'scheduling' AND table_name = ?)")
              .param(table)
              .query(Boolean.class)
              .single();
      assertThat(tableExists).as("Table scheduling.%s must exist", table).isTrue();
    }

    for (String table : new String[] {"outbox", "idempotency_key", "inbox"}) {
      Boolean tableExists =
          jdbc.sql(
                  "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = ? AND table_schema IN ('scheduling', 'public'))")
              .param(table)
              .query(Boolean.class)
              .single();
      assertThat(tableExists).as("Table %s must exist", table).isTrue();
    }
  }

  @Nested
  @DisplayName("Appointment table constraints and indexes")
  class AppointmentTests {

    private UUID createScheduleAndSlot() {
      UUID scheduleId = UUID.randomUUID();
      UUID practitionerId = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO scheduling.schedule (id, version_id, last_updated, active, practitioner_id, specialty, horizon_start, horizon_end, content)
              VALUES (?, 1, now(), true, ?, 'cardiology', '2026-11-01', '2026-11-30', '{}'::jsonb)
              """)
          .params(scheduleId, practitionerId)
          .update();

      UUID slotId = UUID.randomUUID();
      OffsetDateTime start = OffsetDateTime.parse("2026-11-20T08:00:00Z");
      OffsetDateTime end = OffsetDateTime.parse("2026-11-20T08:15:00Z");
      jdbc.sql(
              """
              INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content)
              VALUES (?, 1, now(), ?, ?, 'cardiology', 'free', ?, ?, '{}'::jsonb)
              """)
          .params(slotId, scheduleId, practitionerId, start, end)
          .update();

      return slotId;
    }

    @Test
    @DisplayName("Insert valid appointment successfully")
    void insertValidAppointment() {
      UUID slotId = createScheduleAndSlot();
      UUID appointmentId = UUID.randomUUID();
      UUID patientId = UUID.randomUUID();
      UUID practitionerId = UUID.randomUUID();
      OffsetDateTime start = OffsetDateTime.parse("2026-11-20T08:00:00Z");
      OffsetDateTime end = OffsetDateTime.parse("2026-11-20T08:15:00Z");

      jdbc.sql(
              """
              INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, content)
              VALUES (?, 1, now(), 'booked', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user123', '{"resourceType":"Appointment"}'::jsonb)
              """)
          .params(appointmentId, slotId, patientId, practitionerId, start, end)
          .update();

      Long count =
          jdbc.sql("SELECT count(*) FROM scheduling.appointment WHERE id = ?")
              .param(appointmentId)
              .query(Long.class)
              .single();
      assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("Foreign key constraint: invalid slot_id fails")
    void foreignKeySlotId() {
      UUID nonExistentSlot = UUID.randomUUID();
      assertThatThrownBy(() ->
              jdbc.sql(
                      """
                      INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, content)
                      VALUES (?, 1, now(), 'booked', ?, ?, ?, 'cardiology', now(), now() + interval '15 minutes', 'web', 'user123', '{}'::jsonb)
                      """)
                  .params(UUID.randomUUID(), nonExistentSlot, UUID.randomUUID(), UUID.randomUUID())
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Status and channel check constraints")
    void statusAndChannelChecks() {
      UUID slotId = createScheduleAndSlot();
      OffsetDateTime start = OffsetDateTime.parse("2026-11-20T08:00:00Z");
      OffsetDateTime end = OffsetDateTime.parse("2026-11-20T08:15:00Z");

      // Invalid status 'pending'
      assertThatThrownBy(() ->
              jdbc.sql(
                      """
                      INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, content)
                      VALUES (?, 1, now(), 'pending', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user123', '{}'::jsonb)
                      """)
                  .params(
                      UUID.randomUUID(), slotId, UUID.randomUUID(), UUID.randomUUID(), start, end)
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class);

      // Invalid channel 'mobile' (only 'web', 'chatbot', 'staff' allowed)
      assertThatThrownBy(() ->
              jdbc.sql(
                      """
                      INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, content)
                      VALUES (?, 1, now(), 'booked', ?, ?, ?, 'cardiology', ?, ?, 'mobile', 'user123', '{}'::jsonb)
                      """)
                  .params(
                      UUID.randomUUID(), slotId, UUID.randomUUID(), UUID.randomUUID(), start, end)
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName(
        "ck_appointment_cancel_reason: cancel_reason is required if and only if status is 'cancelled'")
    void cancelReasonCheckConstraint() {
      UUID slotId = createScheduleAndSlot();
      OffsetDateTime start = OffsetDateTime.parse("2026-11-20T08:00:00Z");
      OffsetDateTime end = OffsetDateTime.parse("2026-11-20T08:15:00Z");

      // status = 'cancelled' without cancel_reason -> FAILS
      assertThatThrownBy(() ->
              jdbc.sql(
                      """
                      INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, cancel_reason, content)
                      VALUES (?, 1, now(), 'cancelled', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user123', NULL, '{}'::jsonb)
                      """)
                  .params(
                      UUID.randomUUID(), slotId, UUID.randomUUID(), UUID.randomUUID(), start, end)
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_appointment_cancel_reason");

      // status = 'booked' with non-null cancel_reason -> FAILS
      assertThatThrownBy(() ->
              jdbc.sql(
                      """
                      INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, cancel_reason, content)
                      VALUES (?, 1, now(), 'booked', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user123', 'mistake', '{}'::jsonb)
                      """)
                  .params(
                      UUID.randomUUID(), slotId, UUID.randomUUID(), UUID.randomUUID(), start, end)
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ck_appointment_cancel_reason");

      // status = 'cancelled' with non-null cancel_reason -> SUCCEEDS
      UUID cancelledId = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, cancel_reason, content)
              VALUES (?, 1, now(), 'cancelled', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user123', 'patient_busy', '{}'::jsonb)
              """)
          .params(cancelledId, slotId, UUID.randomUUID(), UUID.randomUUID(), start, end)
          .update();

      Long count =
          jdbc.sql("SELECT count(*) FROM scheduling.appointment WHERE id = ?")
              .param(cancelledId)
              .query(Long.class)
              .single();
      assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("ux_appointment_one_active_per_slot: only one active appointment per slot")
    void uniqueOneActiveAppointmentPerSlot() {
      UUID slotId = createScheduleAndSlot();
      UUID patient1 = UUID.randomUUID();
      UUID patient2 = UUID.randomUUID();
      UUID practitionerId = UUID.randomUUID();
      OffsetDateTime start = OffsetDateTime.parse("2026-11-20T08:00:00Z");
      OffsetDateTime end = OffsetDateTime.parse("2026-11-20T08:15:00Z");

      // First active appointment ('booked') on slotId -> SUCCEEDS
      UUID appt1 = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, content)
              VALUES (?, 1, now(), 'booked', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user1', '{}'::jsonb)
              """)
          .params(appt1, slotId, patient1, practitionerId, start, end)
          .update();

      // Second active appointment ('booked') on SAME slotId -> FAILS
      // ux_appointment_one_active_per_slot
      assertThatThrownBy(() ->
              jdbc.sql(
                      """
                      INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, content)
                      VALUES (?, 1, now(), 'booked', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user2', '{}'::jsonb)
                      """)
                  .params(UUID.randomUUID(), slotId, patient2, practitionerId, start, end)
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ux_appointment_one_active_per_slot");

      // Second active appointment with status 'arrived' or 'fulfilled' on same slotId also fails
      assertThatThrownBy(() ->
              jdbc.sql(
                      """
                      INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, content)
                      VALUES (?, 1, now(), 'arrived', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user2', '{}'::jsonb)
                      """)
                  .params(UUID.randomUUID(), slotId, patient2, practitionerId, start, end)
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("ux_appointment_one_active_per_slot");

      // Cancel the first appointment
      jdbc.sql(
              """
              UPDATE scheduling.appointment
              SET status = 'cancelled', cancel_reason = 'cancelled by patient', last_updated = now()
              WHERE id = ?
              """)
          .param(appt1)
          .update();

      // Now booking a new active appointment on slotId -> SUCCEEDS because the previous is
      // cancelled!
      UUID appt2 = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, content)
              VALUES (?, 1, now(), 'booked', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user2', '{}'::jsonb)
              """)
          .params(appt2, slotId, patient2, practitionerId, start, end)
          .update();

      Long count =
          jdbc.sql("SELECT count(*) FROM scheduling.appointment WHERE slot_id = ?")
              .param(slotId)
              .query(Long.class)
              .single();
      assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("Rescheduled appointment self-reference")
    void rescheduledFromSelfReference() {
      UUID slotId = createScheduleAndSlot();
      UUID appt1 = UUID.randomUUID();
      OffsetDateTime start = OffsetDateTime.parse("2026-11-20T08:00:00Z");
      OffsetDateTime end = OffsetDateTime.parse("2026-11-20T08:15:00Z");

      // Cancel appointment 1
      jdbc.sql(
              """
              INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, cancel_reason, content)
              VALUES (?, 1, now(), 'cancelled', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user1', 'reschedule', '{}'::jsonb)
              """)
          .params(appt1, slotId, UUID.randomUUID(), UUID.randomUUID(), start, end)
          .update();

      // Appointment 2 rescheduled from appt1
      UUID appt2 = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO scheduling.appointment (id, version_id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, rescheduled_from, content)
              VALUES (?, 1, now(), 'booked', ?, ?, ?, 'cardiology', ?, ?, 'web', 'user1', ?, '{}'::jsonb)
              """)
          .params(appt2, slotId, UUID.randomUUID(), UUID.randomUUID(), start, end, appt1)
          .update();

      UUID parentId =
          jdbc.sql("SELECT rescheduled_from FROM scheduling.appointment WHERE id = ?")
              .param(appt2)
              .query(UUID.class)
              .single();
      assertThat(parentId).isEqualTo(appt1);
    }
  }

  @Nested
  @DisplayName("Resource history table constraints")
  class ResourceHistoryTests {

    @Test
    @DisplayName("Insert valid history for Schedule and Appointment")
    void insertValidHistory() {
      UUID scheduleId = UUID.randomUUID();
      UUID appointmentId = UUID.randomUUID();

      // Schedule history
      jdbc.sql(
              """
              INSERT INTO scheduling.resource_history (resource_type, id, version_id, last_updated, operation, changed_by, content)
              VALUES ('Schedule', ?, 1, now(), 'create', 'user1', '{}'::jsonb)
              """)
          .param(scheduleId)
          .update();

      // Appointment history
      jdbc.sql(
              """
              INSERT INTO scheduling.resource_history (resource_type, id, version_id, last_updated, operation, changed_by, content)
              VALUES ('Appointment', ?, 1, now(), 'create', 'user2', '{}'::jsonb)
              """)
          .param(appointmentId)
          .update();

      // Update version 2
      jdbc.sql(
              """
              INSERT INTO scheduling.resource_history (resource_type, id, version_id, last_updated, operation, changed_by, content)
              VALUES ('Appointment', ?, 2, now(), 'update', 'user2', '{}'::jsonb)
              """)
          .param(appointmentId)
          .update();

      Long count =
          jdbc.sql("SELECT count(*) FROM scheduling.resource_history WHERE id = ?")
              .param(appointmentId)
              .query(Long.class)
              .single();
      assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("Invalid resource_type or operation fails check constraint")
    void invalidResourceTypeOrOperation() {
      // Invalid resource_type 'Slot'
      assertThatThrownBy(() ->
              jdbc.sql(
                      """
                      INSERT INTO scheduling.resource_history (resource_type, id, version_id, last_updated, operation, changed_by, content)
                      VALUES ('Slot', ?, 1, now(), 'create', 'user1', '{}'::jsonb)
                      """)
                  .param(UUID.randomUUID())
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class);

      // Invalid operation 'delete'
      assertThatThrownBy(() ->
              jdbc.sql(
                      """
                      INSERT INTO scheduling.resource_history (resource_type, id, version_id, last_updated, operation, changed_by, content)
                      VALUES ('Appointment', ?, 1, now(), 'delete', 'user1', '{}'::jsonb)
                      """)
                  .param(UUID.randomUUID())
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Duplicate (resource_type, id, version_id) fails primary key")
    void duplicatePrimaryKey() {
      UUID id = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO scheduling.resource_history (resource_type, id, version_id, last_updated, operation, changed_by, content)
              VALUES ('Schedule', ?, 1, now(), 'create', 'user1', '{}'::jsonb)
              """)
          .param(id)
          .update();

      assertThatThrownBy(() ->
              jdbc.sql(
                      """
                      INSERT INTO scheduling.resource_history (resource_type, id, version_id, last_updated, operation, changed_by, content)
                      VALUES ('Schedule', ?, 1, now(), 'update', 'user2', '{}'::jsonb)
                      """)
                  .param(id)
                  .update())
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }
}
