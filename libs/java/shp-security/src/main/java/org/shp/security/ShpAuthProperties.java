package org.shp.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication configuration ({@code shp.auth.*}); by default reads env {@code OIDC_AUTHORITY},
 * {@code OIDC_AUDIENCE} (local Helm values already set them).
 *
 * @param authority Keycloak issuer (cloud: https://<domain>/idp/realms/shp; local:
 *     http://.../realms/shp)
 * @param audience audience the token must target (local: shp-api)
 * @param requireMfaForStaff staff must have amr containing "mfa"; disabled locally because the
 *     local realm does not simulate MFA yet (LOC-02)
 * @param clockSkew allowed clock skew when validating token expiry
 * @param jwkSetUri optional: load signing keys from this address instead of via {@code authority}
 *     discovery. Use when the service reaches the IdP through a different internal address than the
 *     token issuer (local: tokens carry iss=http://localhost:18080/..., the in-cluster service
 *     calls http://keycloak.shp-deps...)
 */
@ConfigurationProperties("shp.auth")
public record ShpAuthProperties(
    String authority,
    String audience,
    Boolean requireMfaForStaff,
    Duration clockSkew,
    String jwkSetUri) {
  public ShpAuthProperties {
    authority = authority == null ? "" : authority;
    audience = audience == null ? "" : audience;
    requireMfaForStaff = requireMfaForStaff == null || requireMfaForStaff;
    clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
    jwkSetUri = jwkSetUri == null ? "" : jwkSetUri;
  }
}
