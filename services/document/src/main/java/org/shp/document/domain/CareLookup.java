package org.shp.document.domain;

/** Port to the clinical service's care relationship check (09 §4, 15 §4). */
public interface CareLookup {

  /**
   * True when the practitioner has a valid care relationship (or active break-glass) with the
   * patient.
   *
   * @throws org.shp.web.errors.ShpException {@code dependency-unavailable} when clinical cannot
   *     answer; the caller must treat an unknown answer as a denial
   */
  boolean hasCare(String practitionerId, String patientId);
}
