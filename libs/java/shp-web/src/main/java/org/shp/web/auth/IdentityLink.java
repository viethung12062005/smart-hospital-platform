package org.shp.web.auth;

import java.time.Instant;
import java.util.List;
import org.shp.authz.LinkedProfile;

/**
 * Link between an account (OIDC sub) and Patient/Practitioner, stored in clinical ({@code
 * identity_links}), with the profiles the account manages ({@code profile_links}, ADR-0018).
 *
 * @param fhirUserType Patient or Practitioner
 * @param fhirUserId id (without the type)
 * @param linkedProfiles managed profiles; empty when there are none or the identity service is an
 *     older version that does not return the field
 */
public record IdentityLink(
    String fhirUserType, String fhirUserId, boolean disabled, List<LinkedProfile> linkedProfiles) {
  public IdentityLink {
    linkedProfiles = linkedProfiles == null ? List.of() : List.copyOf(linkedProfiles);
  }

  public IdentityLink(String fhirUserType, String fhirUserId, boolean disabled) {
    this(fhirUserType, fhirUserId, disabled, List.of());
  }

  /** This link without the profiles whose {@code expiresAt} has passed at {@code now}. */
  public IdentityLink activeAt(Instant now) {
    if (linkedProfiles.stream().allMatch(p -> p.activeAt(now))) {
      return this;
    }
    return new IdentityLink(
        fhirUserType,
        fhirUserId,
        disabled,
        linkedProfiles.stream().filter(p -> p.activeAt(now)).toList());
  }
}
