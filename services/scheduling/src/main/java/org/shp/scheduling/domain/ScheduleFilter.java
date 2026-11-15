package org.shp.scheduling.domain;

import java.util.UUID;

/** Filter parameters for querying schedules (corresponds to FHIR Schedule search parameters). */
public record ScheduleFilter(
    UUID practitionerId, String specialty, Boolean active, int limit, int offset) {

  public ScheduleFilter {
    if (limit <= 0) {
      limit = 20;
    }
    if (offset < 0) {
      offset = 0;
    }
  }

  public static ScheduleFilter of(UUID practitionerId, String specialty, Boolean active) {
    return new ScheduleFilter(practitionerId, specialty, active, 20, 0);
  }

  public static ScheduleFilter empty() {
    return new ScheduleFilter(null, null, null, 20, 0);
  }
}
