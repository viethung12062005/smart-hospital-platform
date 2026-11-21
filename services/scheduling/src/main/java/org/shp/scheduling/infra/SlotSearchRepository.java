package org.shp.scheduling.infra;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.shp.scheduling.domain.SlotRecord;
import org.shp.scheduling.domain.SlotSearchCriteria;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only slot search for S8 (02-db-scheduling section 4). Results are ordered by {@code
 * (start_at, id)} and paged with a keyset cursor, so a page never repeats or skips a row when slots
 * are added or booked between requests.
 *
 * <p>The status filter is written into the SQL as literals taken from the closed set of slot
 * statuses: a bound parameter would give a generic plan that cannot use the partial index {@code
 * ix_slot_free_by_specialty} ({@code WHERE status = 'free'}).
 */
@Repository
public class SlotSearchRepository {

  private static final RowMapper<SlotRecord> SLOT_MAPPER =
      (rs, rowNum) ->
          new SlotRecord(
              rs.getObject("id", UUID.class),
              rs.getInt("version_id"),
              rs.getTimestamp("last_updated").toInstant(),
              rs.getObject("schedule_id", UUID.class),
              rs.getObject("practitioner_id", UUID.class),
              rs.getString("specialty"),
              rs.getString("status"),
              rs.getTimestamp("start_at").toInstant(),
              rs.getTimestamp("end_at").toInstant(),
              rs.getString("content"));

  private final JdbcClient jdbc;

  public SlotSearchRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /** Returns up to {@code criteria.limit()} slots in ascending {@code (start_at, id)} order. */
  public List<SlotRecord> search(SlotSearchCriteria criteria) {
    Objects.requireNonNull(criteria, "criteria must not be null");
    Query query = build(criteria);
    return jdbc.sql(query.sql()).params(query.params()).query(SLOT_MAPPER).list();
  }

  /** The {@code EXPLAIN} plan of {@link #search}, used to prove the index is chosen. */
  public String explain(SlotSearchCriteria criteria) {
    Objects.requireNonNull(criteria, "criteria must not be null");
    Query query = build(criteria);
    return jdbc
        .sql("EXPLAIN " + query.sql())
        .params(query.params())
        .query(String.class)
        .list()
        .stream()
        .collect(Collectors.joining("\n"));
  }

  private record Query(String sql, List<Object> params) {}

  private static Query build(SlotSearchCriteria c) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT id, version_id, last_updated, schedule_id, practitioner_id, specialty,
                   status, start_at, end_at, content::text AS content
              FROM scheduling.slot
             WHERE status IN (\
            """);
    // Statuses are validated against SlotRecord.ALLOWED_STATUSES, so literals are safe.
    sql.append(
        new TreeSet<>(c.statuses())
            .stream().map(s -> "'" + s + "'").collect(Collectors.joining(", ")));
    sql.append(")");

    List<Object> params = new ArrayList<>();
    if (c.specialty() != null) {
      sql.append(" AND specialty = ?");
      params.add(c.specialty());
    }
    if (c.practitionerId() != null) {
      sql.append(" AND practitioner_id = ?");
      params.add(c.practitionerId());
    }
    if (c.scheduleId() != null) {
      sql.append(" AND schedule_id = ?");
      params.add(c.scheduleId());
    }
    if (c.startFrom() != null) {
      sql.append(" AND start_at >= ?");
      params.add(Timestamp.from(c.startFrom()));
    }
    if (c.startTo() != null) {
      sql.append(" AND start_at < ?");
      params.add(Timestamp.from(c.startTo()));
    }
    if (c.afterStartAt() != null) {
      sql.append(" AND (start_at, id) > (?, ?)");
      params.add(Timestamp.from(c.afterStartAt()));
      params.add(c.afterId());
    }
    sql.append(" ORDER BY start_at, id LIMIT ?");
    params.add(c.limit());
    return new Query(sql.toString(), params);
  }
}
