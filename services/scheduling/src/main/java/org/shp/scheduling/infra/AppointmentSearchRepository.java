package org.shp.scheduling.infra;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.shp.scheduling.domain.AppointmentSearchCriteria;
import org.shp.scheduling.domain.AppointmentSearchCriteria.AppointmentRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only repository for {@code scheduling.appointment} supporting S12 read by ID and S13
 * keyset-paginated search (02-db-scheduling §4, 08-api-scheduling §4).
 */
@Repository
public class AppointmentSearchRepository {

  private static final RowMapper<AppointmentRow> APPOINTMENT_MAPPER =
      (rs, rowNum) ->
          new AppointmentRow(
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
              rs.getString("content"));

  private final JdbcClient jdbc;

  public AppointmentSearchRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /** Fetch a single appointment by primary key. */
  @Transactional(readOnly = true)
  public Optional<AppointmentRow> findById(UUID id) {
    Objects.requireNonNull(id, "id must not be null");
    return jdbc.sql(
            """
            SELECT id, version_id, last_updated, status, slot_id, patient_id, practitioner_id,
                   specialty, start_at, end_at, content::text AS content
              FROM scheduling.appointment
             WHERE id = ?
            """)
        .param(id)
        .query(APPOINTMENT_MAPPER)
        .optional();
  }

  /** Search appointments using keyset pagination on {@code (start_at, id)}. */
  @Transactional(readOnly = true)
  public List<AppointmentRow> search(AppointmentSearchCriteria criteria) {
    Objects.requireNonNull(criteria, "criteria must not be null");
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT id, version_id, last_updated, status, slot_id, patient_id, practitioner_id,
                   specialty, start_at, end_at, content::text AS content
              FROM scheduling.appointment
             WHERE 1=1
            """);
    List<Object> params = new ArrayList<>();

    if (criteria.patientId() != null) {
      sql.append(" AND patient_id = ?");
      params.add(criteria.patientId());
    }
    if (criteria.practitionerId() != null) {
      sql.append(" AND practitioner_id = ?");
      params.add(criteria.practitionerId());
    }
    if (criteria.specialty() != null) {
      sql.append(" AND specialty = ?");
      params.add(criteria.specialty());
    }
    if (!criteria.statuses().isEmpty()) {
      sql.append(" AND status IN (");
      // Statuses are validated against AppointmentSearchCriteria.ALLOWED_STATUSES.
      sql.append(
          new TreeSet<>(criteria.statuses())
              .stream().map(s -> "'" + s + "'").collect(Collectors.joining(", ")));
      sql.append(")");
    }
    if (criteria.startFrom() != null) {
      sql.append(" AND start_at >= ?");
      params.add(Timestamp.from(criteria.startFrom()));
    }
    if (criteria.startTo() != null) {
      sql.append(" AND start_at < ?");
      params.add(Timestamp.from(criteria.startTo()));
    }
    if (criteria.afterStartAt() != null) {
      if (criteria.descending()) {
        sql.append(" AND (start_at < ? OR (start_at = ? AND id > ?))");
        params.add(Timestamp.from(criteria.afterStartAt()));
        params.add(Timestamp.from(criteria.afterStartAt()));
        params.add(criteria.afterId());
      } else {
        sql.append(" AND (start_at, id) > (?, ?)");
        params.add(Timestamp.from(criteria.afterStartAt()));
        params.add(criteria.afterId());
      }
    }

    if (criteria.descending()) {
      sql.append(" ORDER BY start_at DESC, id ASC LIMIT ?");
    } else {
      sql.append(" ORDER BY start_at ASC, id ASC LIMIT ?");
    }
    params.add(criteria.limit());

    return jdbc.sql(sql.toString()).params(params).query(APPOINTMENT_MAPPER).list();
  }
}
