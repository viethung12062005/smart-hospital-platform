package org.shp.scheduling.infra;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Slot reads and the compare-and-set used by booking (02-db-scheduling §3.1, step 4). */
@Repository
public class SlotRepository {

  /** The slot data booking needs, as returned by the claim or by a re-read. */
  public record SlotFacts(
      UUID id, UUID practitionerId, String specialty, String status, Instant start, Instant end) {}

  private final JdbcClient jdbc;

  public SlotRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  /**
   * Compare-and-set {@code free -> busy} for a slot that starts after {@code now} and before {@code
   * windowEnd} (BR-04). No row lock is held beyond the statement; the caller's transaction keeps
   * the change until commit. Empty means the slot was not claimed (use {@link #find}).
   */
  public Optional<SlotFacts> claimFree(UUID slotId, Instant now, Instant windowEnd) {
    return jdbc.sql(
            """
            UPDATE scheduling.slot
               SET status = 'busy', version_id = version_id + 1, last_updated = ?,
                   content = jsonb_set(content, '{status}', '"busy"')
             WHERE id = ? AND status = 'free' AND start_at > ? AND start_at < ?
            RETURNING id, practitioner_id, specialty, status, start_at, end_at
            """)
        .params(Timestamp.from(now), slotId, Timestamp.from(now), Timestamp.from(windowEnd))
        .query((rs, i) ->
            new SlotFacts(
                rs.getObject("id", UUID.class),
                rs.getObject("practitioner_id", UUID.class),
                rs.getString("specialty"),
                rs.getString("status"),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant()))
        .optional();
  }

  /** Read a slot, to tell why a claim failed. */
  public Optional<SlotFacts> find(UUID slotId) {
    return jdbc.sql(
            "SELECT id, practitioner_id, specialty, status, start_at, end_at FROM scheduling.slot WHERE id = ?")
        .param(slotId)
        .query((rs, i) ->
            new SlotFacts(
                rs.getObject("id", UUID.class),
                rs.getObject("practitioner_id", UUID.class),
                rs.getString("specialty"),
                rs.getString("status"),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant()))
        .optional();
  }
}
