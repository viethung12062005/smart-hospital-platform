package org.shp.scheduling.domain;

import java.util.UUID;

/**
 * Filter parameters for querying schedules (corresponds to FHIR Schedule search parameters). Pages
 * use keyset pagination on the immutable {@code id}: {@code afterId} is the id of the last record
 * of the previous page (null for the first page), results are ordered by id ascending.
 */
public record ScheduleFilter(
    UUID practitionerId, String specialty, Boolean active, int limit, UUID afterId) {

  public ScheduleFilter {
    if (limit <= 0) {
      limit = 20;
    }
  }

  public static ScheduleFilter of(UUID practitionerId, String specialty, Boolean active) {
    return new ScheduleFilter(practitionerId, specialty, active, 20, null);
  }

  public static ScheduleFilter empty() {
    return new ScheduleFilter(null, null, null, 20, null);
  }
}
