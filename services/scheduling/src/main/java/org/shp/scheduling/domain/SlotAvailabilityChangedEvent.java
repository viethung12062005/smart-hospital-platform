package org.shp.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Domain event emitted when free-slot availability changes (booking, slot block/unblock, slot
 * generation, or cancellation/reschedule), used to invalidate matching Redis slot search caches
 * after transaction commit.
 */
public record SlotAvailabilityChangedEvent(
    UUID practitionerId, String specialty, UUID scheduleId, Set<LocalDate> days) {

  private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  public SlotAvailabilityChangedEvent {
    specialty =
        (specialty == null || specialty.isBlank())
            ? null
            : specialty.strip().toLowerCase(Locale.ROOT);
    days = days == null ? Set.of() : Set.copyOf(days);
  }

  public static SlotAvailabilityChangedEvent forSlot(
      UUID practitionerId, String specialty, UUID scheduleId, Instant startAt) {
    Set<LocalDate> days =
        startAt == null ? Set.of() : Set.of(LocalDate.ofInstant(startAt, VN_ZONE));
    return new SlotAvailabilityChangedEvent(practitionerId, specialty, scheduleId, days);
  }
}
