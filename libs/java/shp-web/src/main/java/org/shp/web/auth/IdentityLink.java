package org.shp.web.auth;

/**
 * Link between an account (OIDC sub) and Patient/Practitioner, stored in clinical ({@code
 * identity_links}).
 *
 * @param fhirUserType Patient or Practitioner
 * @param fhirUserId id (without the type)
 */
public record IdentityLink(String fhirUserType, String fhirUserId, boolean disabled) {}
