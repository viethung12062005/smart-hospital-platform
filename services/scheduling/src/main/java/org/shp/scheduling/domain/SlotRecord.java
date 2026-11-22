package org.shp.scheduling.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Domain model representing an examination time slot (FHIR Slot). Corresponds to table {@code
 * scheduling.slot}.
 */
public record SlotRecord(
    UUID id,
    int versionId,
    Instant lastUpdated,
    UUID scheduleId,
    UUID practitionerId,
    String specialty,
    String status,
    Instant startAt,
    Instant endAt,
    String content) {

  public static final Set<String> ALLOWED_STATUSES = Set.of("free", "busy", "busy-unavailable");

  public SlotRecord {
    Objects.requireNonNull(id, "id must not be null");
    if (versionId < 1) {
      throw new IllegalArgumentException("versionId must be >= 1");
    }
    Objects.requireNonNull(lastUpdated, "lastUpdated must not be null");
    Objects.requireNonNull(scheduleId, "scheduleId must not be null");
    Objects.requireNonNull(practitionerId, "practitionerId must not be null");
    Objects.requireNonNull(specialty, "specialty must not be null");
    Objects.requireNonNull(status, "status must not be null");
    if (!ALLOWED_STATUSES.contains(status)) {
      throw new IllegalArgumentException("status must be one of free, busy, busy-unavailable");
    }
    Objects.requireNonNull(startAt, "startAt must not be null");
    Objects.requireNonNull(endAt, "endAt must not be null");
    if (!endAt.isAfter(startAt)) {
      throw new IllegalArgumentException("endAt must be after startAt");
    }
    Objects.requireNonNull(content, "content must not be null");
  }
}
