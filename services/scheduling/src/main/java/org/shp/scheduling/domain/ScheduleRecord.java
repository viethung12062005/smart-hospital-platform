package org.shp.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain model representing a doctor's work schedule for a specialty (FHIR Schedule). Corresponds
 * to table {@code scheduling.schedule}.
 */
public record ScheduleRecord(
    UUID id,
    int versionId,
    Instant lastUpdated,
    boolean active,
    UUID practitionerId,
    String specialty,
    LocalDate horizonStart,
    LocalDate horizonEnd,
    String content) {

  public ScheduleRecord {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(practitionerId, "practitionerId must not be null");
    Objects.requireNonNull(specialty, "specialty must not be null");
    Objects.requireNonNull(horizonStart, "horizonStart must not be null");
    Objects.requireNonNull(horizonEnd, "horizonEnd must not be null");
    Objects.requireNonNull(content, "content must not be null");
    if (lastUpdated == null) {
      lastUpdated = Instant.now();
    }
    if (versionId < 1) {
      versionId = 1;
    }
    if (horizonEnd.isBefore(horizonStart)) {
      throw new IllegalArgumentException("horizonEnd must be >= horizonStart");
    }
  }

  public ScheduleRecord withUpdate(
      Instant newLastUpdated,
      boolean newActive,
      LocalDate newHorizonStart,
      LocalDate newHorizonEnd,
      String newContent) {
    return new ScheduleRecord(
        id,
        versionId + 1,
        newLastUpdated,
        newActive,
        practitionerId,
        specialty,
        newHorizonStart,
        newHorizonEnd,
        newContent);
  }
}
