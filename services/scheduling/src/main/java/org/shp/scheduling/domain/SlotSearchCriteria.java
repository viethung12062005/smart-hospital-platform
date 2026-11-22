package org.shp.scheduling.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Filters of a slot search (S8). At least one of {@code specialty}, {@code practitionerId} or
 * {@code scheduleId} is required. {@code startFrom} is inclusive and {@code startTo} exclusive.
 * {@code afterStartAt} and {@code afterId} together are the keyset cursor: only slots strictly
 * after that (start_at, id) pair are returned.
 */
public record SlotSearchCriteria(
    Set<String> statuses,
    String specialty,
    UUID practitionerId,
    UUID scheduleId,
    Instant startFrom,
    Instant startTo,
    Instant afterStartAt,
    UUID afterId,
    int limit) {

  public SlotSearchCriteria {
    Objects.requireNonNull(statuses, "statuses must not be null");
    if (statuses.isEmpty() || !SlotRecord.ALLOWED_STATUSES.containsAll(statuses)) {
      throw new IllegalArgumentException(
          "statuses must be a non-empty subset of the slot statuses");
    }
    statuses = Set.copyOf(statuses);
    if (specialty == null && practitionerId == null && scheduleId == null) {
      throw new IllegalArgumentException("specialty, practitionerId or scheduleId is required");
    }
    if ((afterStartAt == null) != (afterId == null)) {
      throw new IllegalArgumentException("afterStartAt and afterId must be set together");
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be >= 1");
    }
  }
}
