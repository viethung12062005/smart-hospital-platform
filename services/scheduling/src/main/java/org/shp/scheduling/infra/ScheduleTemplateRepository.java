package org.shp.scheduling.infra;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.shp.scheduling.domain.ScheduleTemplateRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Repository for {@code scheduling.schedule_template} rows. */
@Repository
public class ScheduleTemplateRepository {

  private static final RowMapper<ScheduleTemplateRecord> TEMPLATE_MAPPER =
      (rs, rowNum) -> {
        Date validTo = rs.getDate("valid_to");
        return new ScheduleTemplateRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("schedule_id", UUID.class),
            rs.getInt("weekday"),
            rs.getTime("start_time").toLocalTime(),
            rs.getTime("end_time").toLocalTime(),
            rs.getInt("slot_minutes"),
            rs.getDate("valid_from").toLocalDate(),
            validTo != null ? validTo.toLocalDate() : null,
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("created_by"));
      };

  private final JdbcClient jdbc;
  private final Clock clock;

  @Autowired
  public ScheduleTemplateRepository(JdbcClient jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public ScheduleTemplateRepository(JdbcClient jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  /** Return all weekly templates for a schedule, ordered by weekday and start time. */
  @Transactional(readOnly = true)
  public List<ScheduleTemplateRecord> findByScheduleId(UUID scheduleId) {
    Objects.requireNonNull(scheduleId, "scheduleId must not be null");
    return jdbc.sql(
            """
            SELECT id, schedule_id, weekday, start_time, end_time, slot_minutes,
                   valid_from, valid_to, created_at, created_by
            FROM scheduling.schedule_template
            WHERE schedule_id = ?
            ORDER BY weekday ASC, start_time ASC, id ASC
            """)
        .param(scheduleId)
        .query(TEMPLATE_MAPPER)
        .list();
  }

  /**
   * Replace all weekly templates for a schedule inside a single transaction.
   *
   * @return the persisted templates sorted by weekday and start time
   */
  @Transactional
  public List<ScheduleTemplateRecord> replaceAll(
      UUID scheduleId, List<ScheduleTemplateRecord> templates, String createdBy) {
    Objects.requireNonNull(scheduleId, "scheduleId must not be null");
    Objects.requireNonNull(templates, "templates must not be null");
    Objects.requireNonNull(createdBy, "createdBy must not be null");
    if (createdBy.isBlank()) {
      throw new IllegalArgumentException("createdBy must not be blank");
    }

    Instant now = clock.instant();

    jdbc.sql("DELETE FROM scheduling.schedule_template WHERE schedule_id = ?")
        .param(scheduleId)
        .update();

    for (ScheduleTemplateRecord t : templates) {
      jdbc.sql(
              """
              INSERT INTO scheduling.schedule_template (
                  id, schedule_id, weekday, start_time, end_time, slot_minutes,
                  valid_from, valid_to, created_at, created_by
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              """)
          .params(
              t.id(),
              scheduleId,
              t.weekday(),
              Time.valueOf(t.startTime()),
              Time.valueOf(t.endTime()),
              t.slotMinutes(),
              Date.valueOf(t.validFrom()),
              t.validTo() != null ? Date.valueOf(t.validTo()) : null,
              Timestamp.from(now),
              createdBy)
          .update();
    }

    return findByScheduleId(scheduleId);
  }
}
