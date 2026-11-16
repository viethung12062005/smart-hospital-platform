package org.shp.scheduling.domain;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Interface to verify practitioner existence and active specialties via clinical service. Stubbed
 * for SCH-04; wired to real clinical REST API in SCH-33.
 */
public interface PractitionerRoleClient {

  /**
   * Returns list of active specialties for the practitioner. Returns empty list if practitioner
   * does not exist or has no active roles.
   */
  List<String> findActiveSpecialties(UUID practitionerId);

  /** Checks whether the practitioner has an active role matching the given specialty. */
  default boolean hasActiveSpecialty(UUID practitionerId, String specialty) {
    if (practitionerId == null || specialty == null) {
      return false;
    }
    String norm = specialty.strip().toLowerCase(Locale.ROOT);
    return findActiveSpecialties(practitionerId).stream()
        .anyMatch(s -> s.strip().toLowerCase(Locale.ROOT).equals(norm));
  }
}
