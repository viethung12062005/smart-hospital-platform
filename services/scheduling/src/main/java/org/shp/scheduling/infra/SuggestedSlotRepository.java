package org.shp.scheduling.infra;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.shp.scheduling.domain.SuggestedSlot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Finds the nearest free slots to offer after a booking conflict. */
@Repository
public class SuggestedSlotRepository {
  private final JdbcClient jdbc;

  public SuggestedSlotRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /**
   * The {@code limit} soonest free slots inside the booking window for the specialty; the same
   * doctor's slots come first, other doctors of the specialty fill the rest. The taken slot is
   * excluded. Uses {@code ix_slot_free_by_specialty}.
   */
  public List<SuggestedSlot> nearestFree(
      String specialty,
      UUID practitionerId,
      UUID excludeSlot,
      Instant now,
      Instant windowEnd,
      int limit) {
    return jdbc.sql(
            """
            SELECT id, practitioner_id, start_at, end_at
              FROM scheduling.slot
             WHERE status = 'free' AND specialty = ? AND start_at > ? AND start_at < ? AND id <> ?
             ORDER BY (practitioner_id = ?) DESC, start_at, id
             LIMIT ?
            """)
        .params(
            specialty,
            Timestamp.from(now),
            Timestamp.from(windowEnd),
            excludeSlot,
            practitionerId,
            limit)
        .query((rs, i) ->
            new SuggestedSlot(
                rs.getObject("id", UUID.class),
                rs.getObject("practitioner_id", UUID.class),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant()))
        .list();
  }
}
