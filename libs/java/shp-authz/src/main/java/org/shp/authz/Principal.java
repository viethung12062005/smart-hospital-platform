package org.shp.authz;

import java.util.Set;

/**
 * Người gọi, lấy từ token đã xác thực và identity link (không bao giờ từ header do client gửi).
 *
 * @param subject OIDC subject
 * @param roles role từ claim {@code roles}
 * @param fhirUserId id của Patient hoặc Practitioner đã liên kết; null nếu chưa liên kết
 */
public record Principal(String subject, Set<String> roles, String fhirUserId) {
  public Principal {
    roles = Set.copyOf(roles);
  }
}
