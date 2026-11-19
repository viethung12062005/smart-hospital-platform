package org.shp.authz;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Caller, derived from the authenticated token and the identity link (never from a client-supplied
 * header).
 *
 * @param subject OIDC subject
 * @param roles roles from the {@code roles} claim
 * @param fhirUserId id of the linked Patient or Practitioner; null if not linked yet
 * @param linkedProfiles profiles the account manages (ADR-0018); empty when there are none
 */
public record Principal(
    String subject, Set<String> roles, String fhirUserId, List<LinkedProfile> linkedProfiles) {
  public Principal {
    roles = Set.copyOf(roles);
    linkedProfiles = linkedProfiles == null ? List.of() : List.copyOf(linkedProfiles);
  }

  public Principal(String subject, Set<String> roles, String fhirUserId) {
    this(subject, roles, fhirUserId, List.of());
  }

  /** The unexpired link from this caller to {@code patientId}, if any. */
  public Optional<LinkedProfile> linkTo(String patientId, Instant now) {
    if (patientId == null) {
      return Optional.empty();
    }
    return linkedProfiles.stream()
        .filter(l -> patientId.equals(l.patientId()) && l.activeAt(now))
        .findFirst();
  }
}
