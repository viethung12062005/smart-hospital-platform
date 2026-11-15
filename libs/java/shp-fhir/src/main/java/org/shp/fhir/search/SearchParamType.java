package org.shp.fhir.search;

/** The FHIR search parameter types SHP supports (ADR-0003, 07 §4). */
public enum SearchParamType {
  /** {@code code} or {@code system|code}; a comma means OR. */
  TOKEN,
  /** {@code Type/id} or {@code id}; a comma means OR. */
  REFERENCE,
  /**
   * A date/time with a {@code eq|ge|gt|le|lt} prefix; the precision determines the interval (year,
   * month, day…).
   */
  DATE,
  /** A free string (e.g. a name); the service normalizes the match. */
  STRING
}
