package org.shp.web.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình xác thực ({@code shp.auth.*}); mặc định đọc env {@code OIDC_AUTHORITY}, {@code
 * OIDC_AUDIENCE} (Helm values local đã đặt).
 *
 * @param authority issuer của IdP (Entra: https://login.microsoftonline.com/{tenant}/v2.0;
 *     Keycloak: http://.../realms/shp)
 * @param audience audience token phải nhắm tới (local: shp-api)
 * @param requireMfaForStaff nhân viên phải có amr chứa "mfa"; tắt ở local vì realm local chưa mô
 *     phỏng MFA (LOC-02)
 * @param clockSkew độ lệch đồng hồ cho phép khi kiểm hạn token
 */
@ConfigurationProperties("shp.auth")
public record ShpAuthProperties(
    String authority, String audience, Boolean requireMfaForStaff, Duration clockSkew) {
  public ShpAuthProperties {
    authority = authority == null ? "" : authority;
    audience = audience == null ? "" : audience;
    requireMfaForStaff = requireMfaForStaff == null || requireMfaForStaff;
    clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
  }
}
