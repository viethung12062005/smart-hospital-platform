package org.shp.scheduling.domain;

import java.time.Instant;
import java.util.UUID;

/** A free slot offered to a patient whose chosen slot was taken (S11, 409 slot-unavailable). */
public record SuggestedSlot(UUID id, UUID practitionerId, Instant start, Instant end) {}
