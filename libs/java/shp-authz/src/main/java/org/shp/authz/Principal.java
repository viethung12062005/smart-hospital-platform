package org.shp.authz;

import java.util.Set;

/**
 * Caller, derived from the authenticated token and the identity link (never from a client-supplied
 * header).
 *
 * @param subject OIDC subject
 * @param roles roles from the {@code roles} claim
 * @param fhirUserId id of the linked Patient or Practitioner; null if not linked yet
 */
public record Principal(String subject, Set<String> roles, String fhirUserId) {
  public Principal {
    roles = Set.copyOf(roles);
  }
}
