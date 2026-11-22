package org.shp.scheduling.domain;

import java.util.UUID;
import org.shp.web.errors.ShpException;

/**
 * 409 {@code slot-unavailable}: the slot is booked, blocked or already in the past. Carries the
 * doctor and specialty of the slot so the API can suggest the nearest free slots.
 */
public class SlotUnavailableException extends ShpException {
  private static final long serialVersionUID = 1L;

  private final UUID slotId;
  private final UUID practitionerId;
  private final String specialty;

  public SlotUnavailableException(UUID slotId, UUID practitionerId, String specialty) {
    super("slot-unavailable");
    this.slotId = slotId;
    this.practitionerId = practitionerId;
    this.specialty = specialty;
  }

  public UUID slotId() {
    return slotId;
  }

  public UUID practitionerId() {
    return practitionerId;
  }

  public String specialty() {
    return specialty;
  }
}
