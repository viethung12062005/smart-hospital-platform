package org.shp.web.auth;

/**
 * Liên kết tài khoản (OIDC sub) ↔ Patient/Practitioner, lưu ở clinical ({@code identity_links}).
 *
 * @param fhirUserType Patient hoặc Practitioner
 * @param fhirUserId id (không kèm loại)
 */
public record IdentityLink(String fhirUserType, String fhirUserId, boolean disabled) {}
