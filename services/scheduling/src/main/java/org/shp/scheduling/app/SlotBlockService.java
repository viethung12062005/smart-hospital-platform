package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import org.hl7.fhir.r4.model.Slot;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.domain.SlotAvailabilityChangedEvent;
import org.shp.scheduling.domain.SlotRecord;
import org.shp.scheduling.infra.SlotRepository;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for blocking (S9) and unblocking (S10) examination slots with optimistic CAS. */
@Service
public class SlotBlockService {

  public static final int MAX_REASON_LENGTH = 200;

  private final SlotRepository slotRepository;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  public SlotBlockService(SlotRepository slotRepository, Clock clock) {
    this(slotRepository, clock, e -> {});
  }

  @Autowired
  public SlotBlockService(
      SlotRepository slotRepository, Clock clock, ApplicationEventPublisher events) {
    this.slotRepository = Objects.requireNonNull(slotRepository, "slotRepository must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
  }

  /**
   * S9: Block a {@code free} slot ({@code free -> busy-unavailable}) with a reason (1-200 chars).
   * Rejects {@code busy} or appointment-backed slots with 409 {@code slot-has-appointment}
   * (AC-02.2), already-blocked slots with 409 {@code invalid-state-transition}, and stale versions
   * with 412 {@code version-conflict}.
   */
  @Transactional
  public SlotRecord block(UUID slotId, long expectedVersion, String reason) {
    Objects.requireNonNull(slotId, "slotId must not be null");
    if (reason == null) {
      throw new ShpException("validation-failed");
    }
    String trimmed = reason.strip();
    if (trimmed.isEmpty() || trimmed.length() > MAX_REASON_LENGTH) {
      throw new ShpException("validation-failed");
    }

    SlotRecord existing =
        slotRepository.findById(slotId).orElseThrow(() -> new ShpException("not-found"));
    if (existing.versionId() != expectedVersion) {
      throw new ShpException("version-conflict");
    }
    if ("busy".equals(existing.status())) {
      throw new ShpException("slot-has-appointment");
    }
    if (!"free".equals(existing.status())) {
      throw new ShpException("invalid-state-transition");
    }
    if (slotRepository.hasActiveAppointment(slotId)) {
      throw new ShpException("slot-has-appointment");
    }

    return transition(
        existing,
        expectedVersion,
        "free",
        "busy-unavailable",
        Slot.SlotStatus.BUSYUNAVAILABLE,
        trimmed,
        true);
  }

  /**
   * S10: Unblock a {@code busy-unavailable} slot ({@code busy-unavailable -> free}) and clear its
   * block reason comment. Rejects any other state with 409 {@code invalid-state-transition}, and
   * stale versions with 412 {@code version-conflict}.
   */
  @Transactional
  public SlotRecord unblock(UUID slotId, long expectedVersion) {
    Objects.requireNonNull(slotId, "slotId must not be null");

    SlotRecord existing =
        slotRepository.findById(slotId).orElseThrow(() -> new ShpException("not-found"));
    if (existing.versionId() != expectedVersion) {
      throw new ShpException("version-conflict");
    }
    if (!"busy-unavailable".equals(existing.status())) {
      throw new ShpException("invalid-state-transition");
    }

    return transition(
        existing, expectedVersion, "busy-unavailable", "free", Slot.SlotStatus.FREE, null, false);
  }

  private SlotRecord transition(
      SlotRecord existing,
      long expectedVersion,
      String expectedStatus,
      String targetStatus,
      Slot.SlotStatus fhirStatus,
      String comment,
      boolean blocking) {
    Instant now = clock.instant();
    int nextVersion = existing.versionId() + 1;

    Slot fhirSlot = FhirJson.parse(existing.content(), Slot.class);
    fhirSlot.setId("Slot/" + existing.id());
    fhirSlot.getMeta().setVersionId(String.valueOf(nextVersion)).setLastUpdated(Date.from(now));
    fhirSlot.setStatus(fhirStatus);
    fhirSlot.setComment(comment);
    String updatedContent = FhirJson.serialize(fhirSlot);

    SlotRecord updated =
        slotRepository
            .updateStatusWithCas(
                existing.id(),
                existing.versionId(),
                expectedStatus,
                targetStatus,
                now,
                updatedContent)
            .orElseThrow(() -> conflictAfterCasMiss(existing.id(), expectedVersion, blocking));
    events.publishEvent(
        SlotAvailabilityChangedEvent.forSlot(
            updated.practitionerId(),
            updated.specialty(),
            updated.scheduleId(),
            updated.startAt()));
    return updated;
  }

  private RuntimeException conflictAfterCasMiss(
      UUID slotId, long expectedVersion, boolean blocking) {
    SlotRecord current =
        slotRepository.findById(slotId).orElseThrow(() -> new ShpException("not-found"));
    if (current.versionId() != expectedVersion) {
      return new ShpException("version-conflict");
    }
    if (blocking && "busy".equals(current.status())) {
      return new ShpException("slot-has-appointment");
    }
    return new ShpException("invalid-state-transition");
  }
}
