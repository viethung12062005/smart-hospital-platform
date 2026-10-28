package org.shp.security;

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
 * @param jwkSetUri tùy chọn: tải khóa ký từ địa chỉ này thay vì qua discovery của {@code
 *     authority}. Dùng khi service tới IdP bằng địa chỉ nội bộ khác với issuer trong token (local:
 *     token có iss=http://localhost:18080/..., service trong cụm gọi http://keycloak.shp-deps...)
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
