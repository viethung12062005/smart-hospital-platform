package org.shp.scheduling.infra;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.shp.scheduling.domain.ResourceHistoryRecord;
import org.shp.scheduling.domain.ScheduleFilter;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for FHIR Schedule resources, enforcing optimistic locking CAS on updates and recording
 * version history in {@code resource_history}.
 */
@Repository
public class ScheduleRepository {

  private static final RowMapper<ScheduleRecord> SCHEDULE_MAPPER =
      (rs, rowNum) ->
          new ScheduleRecord(
              rs.getObject("id", UUID.class),
              rs.getInt("version_id"),
              rs.getTimestamp("last_updated").toInstant(),
              rs.getBoolean("active"),
              rs.getObject("practitioner_id", UUID.class),
              rs.getString("specialty"),
              rs.getDate("horizon_start").toLocalDate(),
              rs.getDate("horizon_end").toLocalDate(),
              rs.getString("content"));

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
  private final Clock clock;

  @Autowired
  public ScheduleRepository(JdbcClient jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public ScheduleRepository(JdbcClient jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  /** Create a new schedule and insert the initial history entry (version 1, operation 'create'). */
  @Transactional
  public ScheduleRecord create(ScheduleRecord record, String changedBy) {
    Objects.requireNonNull(record, "record must not be null");
    Objects.requireNonNull(changedBy, "changedBy must not be null");
    if (changedBy.isBlank()) {
      throw new IllegalArgumentException("changedBy must not be blank");
    }

    int version = record.versionId() > 0 ? record.versionId() : 1;
    Instant now = clock.instant();

    jdbc.sql(
            """
            INSERT INTO scheduling.schedule (
                id, version_id, last_updated, active, practitioner_id,
                specialty, horizon_start, horizon_end, content
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """)
        .params(
            record.id(),
            version,
            Timestamp.from(now),
            record.active(),
            record.practitionerId(),
            record.specialty(),
            Date.valueOf(record.horizonStart()),
            Date.valueOf(record.horizonEnd()),
            record.content())
        .update();

    jdbc.sql(
            """
            INSERT INTO scheduling.resource_history (
                resource_type, id, version_id, last_updated, operation, changed_by, content
            ) VALUES ('Schedule', ?, ?, ?, 'create', ?, ?::jsonb)
            """)
        .params(record.id(), version, Timestamp.from(now), changedBy, record.content())
        .update();

    return new ScheduleRecord(
        record.id(),
        version,
        now,
        record.active(),
        record.practitionerId(),
        record.specialty(),
        record.horizonStart(),
        record.horizonEnd(),
        record.content());
  }

  /** Look up a schedule by primary key. */
  @Transactional(readOnly = true)
  public Optional<ScheduleRecord> findById(UUID id) {
    Objects.requireNonNull(id, "id must not be null");
    return jdbc.sql(
            """
            SELECT id, version_id, last_updated, active, practitioner_id,
                   specialty, horizon_start, horizon_end, content
            FROM scheduling.schedule
            WHERE id = ?
            """)
        .param(id)
        .query(SCHEDULE_MAPPER)
        .optional();
  }

  /**
   * Search schedules with optional filtering by practitioner, specialty, and active status, in
   * keyset order (id ascending, after {@code filter.afterId()}).
   */
  @Transactional(readOnly = true)
  public List<ScheduleRecord> find(ScheduleFilter filter) {
    Objects.requireNonNull(filter, "filter must not be null");
    var sql =
        new StringBuilder(
            """
            SELECT id, version_id, last_updated, active, practitioner_id,
                   specialty, horizon_start, horizon_end, content
            FROM scheduling.schedule
            WHERE 1=1
            """);
    var params = new ArrayList<Object>();

    if (filter.practitionerId() != null) {
      sql.append(" AND practitioner_id = ?");
      params.add(filter.practitionerId());
    }
    if (filter.specialty() != null) {
      sql.append(" AND specialty = ?");
      params.add(filter.specialty());
    }
    if (filter.active() != null) {
      sql.append(" AND active = ?");
      params.add(filter.active());
    }

    if (filter.afterId() != null) {
      sql.append(" AND id > ?");
      params.add(filter.afterId());
    }

    // Keyset order: id is immutable and unique, so pages stay stable while schedules change.
    sql.append(" ORDER BY id ASC LIMIT ?");
    params.add(filter.limit());

    return jdbc.sql(sql.toString()).params(params).query(SCHEDULE_MAPPER).list();
  }

  /**
   * Update a schedule using optimistic locking (compare-and-set on version_id). Inserts an audit
   * entry in {@code resource_history} on success.
   *
   * @throws ShpException with code "version-conflict" (HTTP 412) if version mismatch
   * @throws ShpException with code "not-found" (HTTP 404) if the schedule does not exist
   */
  @Transactional
  public ScheduleRecord update(ScheduleRecord record, int expectedVersion, String changedBy) {
    Objects.requireNonNull(record, "record must not be null");
    Objects.requireNonNull(changedBy, "changedBy must not be null");
    if (changedBy.isBlank()) {
      throw new IllegalArgumentException("changedBy must not be blank");
    }

    Instant now = clock.instant();
    int newVersion = expectedVersion + 1;

    int rows =
        jdbc.sql(
                """
                UPDATE scheduling.schedule
                SET version_id = ?,
                    last_updated = ?,
                    active = ?,
                    practitioner_id = ?,
                    specialty = ?,
                    horizon_start = ?,
                    horizon_end = ?,
                    content = ?::jsonb
                WHERE id = ? AND version_id = ?
                """)
            .params(
                newVersion,
                Timestamp.from(now),
                record.active(),
                record.practitionerId(),
                record.specialty(),
                Date.valueOf(record.horizonStart()),
                Date.valueOf(record.horizonEnd()),
                record.content(),
                record.id(),
                expectedVersion)
            .update();

    if (rows == 0) {
      var existing = findById(record.id());
      if (existing.isEmpty()) {
        throw new ShpException("not-found");
      }
      throw new ShpException("version-conflict");
    }

    jdbc.sql(
            """
            INSERT INTO scheduling.resource_history (
                resource_type, id, version_id, last_updated, operation, changed_by, content
            ) VALUES ('Schedule', ?, ?, ?, 'update', ?, ?::jsonb)
            """)
        .params(record.id(), newVersion, Timestamp.from(now), changedBy, record.content())
        .update();

    return new ScheduleRecord(
        record.id(),
        newVersion,
        now,
        record.active(),
        record.practitionerId(),
        record.specialty(),
        record.horizonStart(),
        record.horizonEnd(),
        record.content());
  }

  /** Retrieve all history versions for a schedule, ordered by version_id ascending. */
  @Transactional(readOnly = true)
  public List<ResourceHistoryRecord> findHistory(UUID id) {
    Objects.requireNonNull(id, "id must not be null");
    return jdbc.sql(
            """
            SELECT resource_type, id, version_id, last_updated, operation, changed_by, content
            FROM scheduling.resource_history
            WHERE resource_type = 'Schedule' AND id = ?
            ORDER BY version_id ASC
            """)
        .param(id)
        .query(HISTORY_MAPPER)
        .list();
  }

  /** Retrieve a specific history version for a schedule. */
  @Transactional(readOnly = true)
  public Optional<ResourceHistoryRecord> findHistoryVersion(UUID id, int versionId) {
    Objects.requireNonNull(id, "id must not be null");
    return jdbc.sql(
            """
            SELECT resource_type, id, version_id, last_updated, operation, changed_by, content
            FROM scheduling.resource_history
            WHERE resource_type = 'Schedule' AND id = ? AND version_id = ?
            """)
        .params(id, versionId)
        .query(HISTORY_MAPPER)
        .optional();
  }
}
