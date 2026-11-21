package org.shp.scheduling.app;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Synthetic rows for the booking tests: one schedule, slots and appointments written by SQL. */
public final class BookingTestSupport {
  private final JdbcClient jdbc;

  public BookingTestSupport(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void clean() {
    jdbc.sql(
            "TRUNCATE scheduling.appointment, scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history, outbox, idempotency_key CASCADE")
        .update();
  }

  /** An active schedule for a synthetic doctor; returns its id. */
  public UUID schedule(UUID practitionerId, String specialty) {
    UUID id = UUID.randomUUID();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    jdbc.sql(
            "INSERT INTO scheduling.schedule (id, last_updated, active, practitioner_id, specialty, horizon_start, horizon_end, content) VALUES (?, now(), true, ?, ?, ?, ?, '{}'::jsonb)")
        .params(
            id, practitionerId, specialty, Date.valueOf(today), Date.valueOf(today.plusDays(30)))
        .update();
    return id;
  }

  /** A slot of the given status; returns its id. */
  public UUID slot(
      UUID scheduleId, UUID practitionerId, String specialty, String status, Instant start) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO scheduling.slot (id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content) VALUES (?, now(), ?, ?, ?, ?, ?, ?, jsonb_build_object('resourceType', 'Slot', 'status', ?::text))")
        .params(
            id,
            scheduleId,
            practitionerId,
            specialty,
            status,
            Timestamp.from(start),
            Timestamp.from(start.plusSeconds(900)),
            status)
        .update();
    return id;
  }

  /** An appointment of the given status for the slot; returns its id. */
  public UUID appointment(UUID slotId, UUID patientId, String status, Instant start) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO scheduling.appointment (id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, cancel_reason, content) SELECT ?, now(), ?, s.id, ?, s.practitioner_id, s.specialty, ?, ?, 'web', 'seed', CASE WHEN ? = 'cancelled' THEN 'patient-request' END, '{}'::jsonb FROM scheduling.slot s WHERE s.id = ?")
        .params(
            id,
            status,
            patientId,
            Timestamp.from(start),
            Timestamp.from(start.plusSeconds(900)),
            status,
            slotId)
        .update();
    return id;
  }
}
