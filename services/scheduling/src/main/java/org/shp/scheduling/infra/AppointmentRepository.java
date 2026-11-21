package org.shp.scheduling.infra;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.shp.scheduling.domain.AppointmentRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Appointment persistence. Writes run in the caller's transaction (the booking transaction). */
@Repository
public class AppointmentRepository {
  private static final RowMapper<AppointmentRecord> MAPPER =
      (rs, i) ->
          new AppointmentRecord(
              rs.getObject("id", UUID.class),
              rs.getInt("version_id"),
              rs.getTimestamp("last_updated").toInstant(),
              rs.getString("status"),
              rs.getObject("slot_id", UUID.class),
              rs.getObject("patient_id", UUID.class),
              rs.getObject("practitioner_id", UUID.class),
              rs.getString("specialty"),
              rs.getTimestamp("start_at").toInstant(),
              rs.getTimestamp("end_at").toInstant(),
              rs.getString("channel"),
              rs.getString("booked_by"),
              rs.getString("content"));

  private final JdbcClient jdbc;

  public AppointmentRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /**
   * Insert a new appointment and its first {@code resource_history} row. The unique index {@code
   * ux_appointment_one_active_per_slot} is the final guard against double-booking: a violation
   * surfaces as {@link org.springframework.dao.DuplicateKeyException}.
   */
  public void insertWithHistory(AppointmentRecord a) {
    jdbc.sql(
            """
            INSERT INTO scheduling.appointment (
                id, version_id, last_updated, status, slot_id, patient_id, practitioner_id,
                specialty, start_at, end_at, channel, booked_by, content
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """)
        .params(
            a.id(),
            a.versionId(),
            Timestamp.from(a.lastUpdated()),
            a.status(),
            a.slotId(),
            a.patientId(),
            a.practitionerId(),
            a.specialty(),
            Timestamp.from(a.start()),
            Timestamp.from(a.end()),
            a.channel(),
            a.bookedBy(),
            a.content())
        .update();
    jdbc.sql(
            """
            INSERT INTO scheduling.resource_history (
                resource_type, id, version_id, last_updated, operation, changed_by, content
            ) VALUES ('Appointment', ?, ?, ?, 'create', ?, ?::jsonb)
            """)
        .params(a.id(), a.versionId(), Timestamp.from(a.lastUpdated()), a.bookedBy(), a.content())
        .update();
  }

  public Optional<AppointmentRecord> findById(UUID id) {
    return jdbc.sql(
            """
            SELECT id, version_id, last_updated, status, slot_id, patient_id, practitioner_id,
                   specialty, start_at, end_at, channel, booked_by, content
              FROM scheduling.appointment WHERE id = ?
            """)
        .param(id)
        .query(MAPPER)
        .optional();
  }
}
