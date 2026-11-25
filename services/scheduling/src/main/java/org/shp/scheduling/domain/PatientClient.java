package org.shp.scheduling.domain;

import java.util.UUID;

/**
 * Lookups of patient facts in the clinical service. Scheduling holds no patient table, so {@code
 * $book} asks clinical whether the patient exists (404 otherwise).
 */
public interface PatientClient {

  /**
   * Whether the patient exists in clinical.
   *
   * @throws org.shp.web.errors.ShpException {@code dependency-unavailable} when clinical cannot
   *     answer
   */
  boolean exists(UUID patientId);
}
