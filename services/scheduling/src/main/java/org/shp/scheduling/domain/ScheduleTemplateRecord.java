package org.shp.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Domain model representing a weekly schedule template row used to generate examination slots.
 * Corresponds to table {@code scheduling.schedule_template}.
 */
public record ScheduleTemplateRecord(
    UUID id,
    UUID scheduleId,
    int weekday,
    LocalTime startTime,
    LocalTime endTime,
    int slotMinutes,
    LocalDate validFrom,
    LocalDate validTo,
    Instant createdAt,
    String createdBy) {

  public static final Set<Integer> ALLOWED_SLOT_MINUTES = Set.of(10, 15, 20, 30);

  public ScheduleTemplateRecord {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(scheduleId, "scheduleId must not be null");
    if (weekday < 1 || weekday > 7) {
      throw new IllegalArgumentException("weekday must be between 1 and 7");
    }
    Objects.requireNonNull(startTime, "startTime must not be null");
    Objects.requireNonNull(endTime, "endTime must not be null");
    if (!endTime.isAfter(startTime)) {
      throw new IllegalArgumentException("endTime must be after startTime");
    }
    if (!ALLOWED_SLOT_MINUTES.contains(slotMinutes)) {
      throw new IllegalArgumentException("slotMinutes must be one of 10, 15, 20, 30");
    }
    Objects.requireNonNull(validFrom, "validFrom must not be null");
    if (validTo != null && validTo.isBefore(validFrom)) {
      throw new IllegalArgumentException("validTo must be >= validFrom");
    }
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(createdBy, "createdBy must not be null");
    if (createdBy.isBlank()) {
      throw new IllegalArgumentException("createdBy must not be blank");
    }
  }
}
