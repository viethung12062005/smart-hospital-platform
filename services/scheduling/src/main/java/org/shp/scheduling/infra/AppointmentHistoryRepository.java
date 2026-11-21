package org.shp.scheduling.infra;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.shp.scheduling.domain.ResourceHistoryRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read repository for FHIR {@code Appointment} version history stored in {@code
 * scheduling.resource_history} and owning patient lookup from {@code scheduling.appointment}.
 */
@Repository
public class AppointmentHistoryRepository {

  private static final RowMapper<ResourceHistoryRecord> HISTORY_MAPPER =
      (rs, rowNum) ->
          new ResourceHistoryRecord(
              rs.getString("resource_type"),
              rs.getObject("id", UUID.class),
              rs.getInt("version_id"),
              rs.getTimestamp("last_updated").toInstant(),
              rs.getString("operation"),
              rs.getString("changed_by"),
              rs.getString("content"));

  private final JdbcClient jdbc;

  public AppointmentHistoryRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /**
   * Look up the patient id for an appointment so authorization audit records can attribute access.
   */
  @Transactional(readOnly = true)
  public Optional<UUID> findPatientId(UUID appointmentId) {
    Objects.requireNonNull(appointmentId, "appointmentId must not be null");
    return jdbc.sql("SELECT patient_id FROM scheduling.appointment WHERE id = ?")
        .param(appointmentId)
        .query((rs, rowNum) -> rs.getObject("patient_id", UUID.class))
        .optional();
  }

  /** Retrieve all stored versions of an appointment, newest first ({@code version_id DESC}). */
  @Transactional(readOnly = true)
  public List<ResourceHistoryRecord> findHistory(UUID appointmentId) {
    Objects.requireNonNull(appointmentId, "appointmentId must not be null");
    return jdbc.sql(
            """
            SELECT resource_type, id, version_id, last_updated, operation, changed_by, content
              FROM scheduling.resource_history
             WHERE resource_type = 'Appointment' AND id = ?
             ORDER BY version_id DESC
            """)
        .param(appointmentId)
        .query(HISTORY_MAPPER)
        .list();
  }
}
