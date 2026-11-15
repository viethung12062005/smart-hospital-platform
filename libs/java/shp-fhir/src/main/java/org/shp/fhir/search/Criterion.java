package org.shp.fhir.search;

import java.time.Instant;
import java.util.List;

/**
 * A parsed criterion with a type. Multiple criteria in one {@link SearchQuery} are AND-ed; multiple
 * values in one criterion (comma-separated) are OR-ed. The store (SHR-10, SHR-11) translates them
 * straight to a DB query.
 */
public sealed interface Criterion {
  String param();

  /** A code with or without a system; a null system matches every system. */
  record Token(String system, String code) {}

  record TokenCriterion(String param, List<Token> anyOf) implements Criterion {}

  /** The id of the {@code targetType} resource (the {@code Type/} prefix removed). */
  record ReferenceCriterion(String param, String targetType, List<String> anyOfIds)
      implements Criterion {}

  /**
   * The normalized interval from the prefix + precision: a value matches when {@code from ≤ t <
   * to}. A null bound means unbounded on that side.
   */
  record DateCriterion(String param, Instant from, Instant to) implements Criterion {}

  record StringCriterion(String param, String value) implements Criterion {}
}
