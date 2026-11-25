package org.shp.scheduling.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Looks up the ten-year age band of a patient in the clinical service (09 §4). Used by the outbox
 * relay to add {@code ageBand} to {@code appointment-booked} (13 §3, E1).
 */
public interface AgeBandClient {

  /**
   * The band such as {@code 40-49}, or {@code unknown} when the patient has no birth date; empty
   * when the patient does not exist.
   *
   * @throws RuntimeException when clinical cannot answer (error, timeout)
   */
  Optional<String> ageBand(UUID patientId);
}
