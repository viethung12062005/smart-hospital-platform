package org.shp.scheduling.infra;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
  public String insertWithHistory(AppointmentRecord a) {
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
    return jdbc.sql(
            """
            INSERT INTO scheduling.resource_history (
                resource_type, id, version_id, last_updated, operation, changed_by, content
            ) VALUES ('Appointment', ?, ?, ?, 'create', ?, ?::jsonb)
            RETURNING content::text
            """)
        .params(a.id(), a.versionId(), Timestamp.from(a.lastUpdated()), a.bookedBy(), a.content())
        .query(String.class)
        .single();
  }

  /** The original booked version, even if the appointment has since changed. */
  public Optional<AppointmentRecord> findOriginalBooking(UUID id) {
    return jdbc.sql(
            """
            SELECT a.id, h.version_id, h.last_updated, 'booked' AS status, a.slot_id,
                   a.patient_id, a.practitioner_id, a.specialty, a.start_at, a.end_at,
                   a.channel, a.booked_by, h.content
              FROM scheduling.appointment a
              JOIN scheduling.resource_history h ON h.id = a.id
             WHERE a.id = ? AND h.resource_type = 'Appointment' AND h.version_id = 1
               AND h.operation = 'create'
            """)
        .param(id)
        .query(MAPPER)
        .optional();
  }

  /**
   * Conditional status change from {@code booked} to {@code arrived} for {@code $check-in} (08 §4
   * S16): succeeds only when the row still has {@code current.versionId()} and status {@code
   * booked}. Writes the {@code resource_history} row of the new version too. Empty means nothing
   * changed.
   */
  public Optional<AppointmentRecord> checkIn(
      AppointmentRecord current, String changedBy, Instant now, String newContent) {
    int newVersion = current.versionId() + 1;
    int rows =
        jdbc.sql(
                """
                UPDATE scheduling.appointment
                   SET status = 'arrived', version_id = ?, last_updated = ?, content = ?::jsonb
                 WHERE id = ? AND version_id = ? AND status = 'booked'
                """)
            .params(newVersion, Timestamp.from(now), newContent, current.id(), current.versionId())
            .update();
    if (rows == 0) {
      return Optional.empty();
    }
    String stored =
        jdbc.sql(
                """
                INSERT INTO scheduling.resource_history (
                    resource_type, id, version_id, last_updated, operation, changed_by, content
                ) VALUES ('Appointment', ?, ?, ?, 'update', ?, ?::jsonb)
                RETURNING content::text
                """)
            .params(current.id(), newVersion, Timestamp.from(now), changedBy, newContent)
            .query(String.class)
            .single();
    return Optional.of(
        new AppointmentRecord(
            current.id(),
            newVersion,
            now,
            "arrived",
            current.slotId(),
            current.patientId(),
            current.practitionerId(),
            current.specialty(),
            current.start(),
            current.end(),
            current.channel(),
            current.bookedBy(),
            stored));
  }

  /**
   * Selects and locks up to {@code limit} {@code booked} appointments whose {@code end_at} is
   * strictly before {@code endBefore}, skipping rows locked by concurrent replicas (02 §3.3).
   */
  public List<AppointmentRecord> lockOverdueBooked(Instant endBefore, int limit) {
    return jdbc.sql(
            """
            SELECT id, version_id, last_updated, status, slot_id, patient_id, practitioner_id,
                   specialty, start_at, end_at, channel, booked_by, content
              FROM scheduling.appointment
             WHERE status = 'booked' AND end_at < ?
             ORDER BY end_at, id
             LIMIT ?
               FOR UPDATE SKIP LOCKED
            """)
        .params(Timestamp.from(endBefore), limit)
        .query(MAPPER)
        .list();
  }

  /**
   * Transitions a locked {@code booked} appointment to {@code noshow} and appends its {@code
   * resource_history} row (02 §3.3).
   */
  public AppointmentRecord markNoShow(
      AppointmentRecord current, String changedBy, Instant now, String newContent) {
    int newVersion = current.versionId() + 1;
    int rows =
        jdbc.sql(
                """
                UPDATE scheduling.appointment
                   SET status = 'noshow', version_id = ?, last_updated = ?, content = ?::jsonb
                 WHERE id = ? AND version_id = ? AND status = 'booked'
                """)
            .params(newVersion, Timestamp.from(now), newContent, current.id(), current.versionId())
            .update();
    if (rows != 1) {
      throw new IllegalStateException(
          "Locked booked appointment could not be transitioned to noshow");
    }
    String stored =
        jdbc.sql(
                """
                INSERT INTO scheduling.resource_history (
                    resource_type, id, version_id, last_updated, operation, changed_by, content
                ) VALUES ('Appointment', ?, ?, ?, 'update', ?, ?::jsonb)
                RETURNING content::text
                """)
            .params(current.id(), newVersion, Timestamp.from(now), changedBy, newContent)
            .query(String.class)
            .single();
    return new AppointmentRecord(
        current.id(),
        newVersion,
        now,
        "noshow",
        current.slotId(),
        current.patientId(),
        current.practitionerId(),
        current.specialty(),
        current.start(),
        current.end(),
        current.channel(),
        current.bookedBy(),
        stored);
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
