package org.shp.scheduling.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Input of {@code $book} after the API validated it.
 *
 * @param subject OIDC subject of the caller (owner of the Idempotency-Key)
 * @param idempotencyKey validated Idempotency-Key header
 * @param requestHash SHA-256 of the canonicalized request body
 * @param channel {@code web}, {@code chatbot} or {@code staff}
 * @param comment optional note of at most 500 characters
 */
public record BookingCommand(
    String subject,
    String idempotencyKey,
    byte[] requestHash,
    UUID slotId,
    UUID patientId,
    String channel,
    String comment) {

  public BookingCommand {
    Objects.requireNonNull(subject, "subject must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    Objects.requireNonNull(requestHash, "requestHash must not be null");
    Objects.requireNonNull(slotId, "slotId must not be null");
    Objects.requireNonNull(patientId, "patientId must not be null");
    Objects.requireNonNull(channel, "channel must not be null");
  }
}
