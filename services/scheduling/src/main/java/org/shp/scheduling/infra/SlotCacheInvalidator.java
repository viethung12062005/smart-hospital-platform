package org.shp.scheduling.infra;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.shp.events.CloudEvent;
import org.shp.scheduling.app.SlotGenerator;
import org.shp.scheduling.domain.SlotAvailabilityChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;

/**
 * Invalidates {@link SlotSearchCache} after local booking/slot transactions commit and on {@code
 * appointment-*} CloudEvents (08 §3, 14-sequences §1).
 */
@Component
public class SlotCacheInvalidator {
  private static final Set<String> APPOINTMENT_EVENT_TYPES =
      Set.of(
          "shp.scheduling.appointment-booked.v1",
          "shp.scheduling.appointment-cancelled.v1",
          "shp.scheduling.appointment-rescheduled.v1");

  private final SlotSearchCache cache;

  public SlotCacheInvalidator(SlotSearchCache cache) {
    this.cache = Objects.requireNonNull(cache, "cache must not be null");
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onSlotAvailabilityChanged(SlotAvailabilityChangedEvent event) {
    if (event == null) {
      return;
    }
    cache.invalidate(event.practitionerId(), event.specialty(), event.scheduleId(), event.days());
  }

  /** Invalidates affected doctor/specialty/day slot caches from an {@code appointment-*} event. */
  public void onAppointmentEvent(CloudEvent event) {
    if (event == null || !APPOINTMENT_EVENT_TYPES.contains(event.type())) {
      return;
    }
    JsonNode data = event.data();
    UUID practitionerId = optionalUuid(data, "practitionerId");
    String specialty = optionalText(data, "specialty");
    Set<LocalDate> days = new LinkedHashSet<>();
    addInstantDay(days, optionalText(data, "start"));
    addInstantDay(days, optionalText(data, "oldStart"));
    addInstantDay(days, optionalText(data, "newStart"));
    cache.invalidate(practitionerId, specialty, null, days);
  }

  private static void addInstantDay(Set<LocalDate> days, String isoInstant) {
    if (isoInstant != null && !isoInstant.isBlank()) {
      days.add(LocalDate.ofInstant(Instant.parse(isoInstant), SlotGenerator.VN_ZONE));
    }
  }

  private static String optionalText(JsonNode data, String field) {
    if (data == null) {
      return null;
    }
    JsonNode node = data.get(field);
    return node != null && !node.isNull() ? node.asString() : null;
  }

  private static UUID optionalUuid(JsonNode data, String field) {
    String raw = optionalText(data, field);
    return raw != null && !raw.isBlank() ? UUID.fromString(raw) : null;
  }
}
