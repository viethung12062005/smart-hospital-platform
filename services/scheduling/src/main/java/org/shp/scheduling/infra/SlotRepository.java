package org.shp.scheduling.infra;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.shp.scheduling.domain.SlotRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Repository for {@code scheduling.slot} (FHIR Slot) rows. */
@Repository
public class SlotRepository {

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

  public SlotRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /**
   * Insert a slot if it does not conflict with an existing slot (including overlapping slots
   * enforced by {@code ex_slot_no_overlap}).
   *
   * @return {@code true} if the slot was inserted, {@code false} if skipped on conflict
   */
  @Transactional
  public boolean insertIfNotOverlapping(SlotRecord slot) {
    Objects.requireNonNull(slot, "slot must not be null");
    int rows =
        jdbc.sql(
                """
                INSERT INTO scheduling.slot (
                    id, version_id, last_updated, schedule_id, practitioner_id,
                    specialty, status, start_at, end_at, content
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT DO NOTHING
                """)
            .params(
                slot.id(),
                slot.versionId(),
                Timestamp.from(slot.lastUpdated()),
                slot.scheduleId(),
                slot.practitionerId(),
                slot.specialty(),
                slot.status(),
                Timestamp.from(slot.startAt()),
                Timestamp.from(slot.endAt()),
                slot.content())
            .update();
    return rows == 1;
  }

  /** Return all slots for a schedule, ordered chronologically. */
  @Transactional(readOnly = true)
  public List<SlotRecord> findByScheduleId(UUID scheduleId) {
    Objects.requireNonNull(scheduleId, "scheduleId must not be null");
    return jdbc.sql(
            """
            SELECT id, version_id, last_updated, schedule_id, practitioner_id,
                   specialty, status, start_at, end_at, content
            FROM scheduling.slot
            WHERE schedule_id = ?
            ORDER BY start_at ASC, id ASC
            """)
        .param(scheduleId)
        .query(SLOT_MAPPER)
        .list();
  }

  /** Look up a single slot by primary key. */
  @Transactional(readOnly = true)
  public Optional<SlotRecord> findById(UUID id) {
    Objects.requireNonNull(id, "id must not be null");
    return jdbc.sql(
            """
            SELECT id, version_id, last_updated, schedule_id, practitioner_id,
                   specialty, status, start_at, end_at, content
            FROM scheduling.slot
            WHERE id = ?
            """)
        .param(id)
        .query(SLOT_MAPPER)
        .optional();
  }
}
