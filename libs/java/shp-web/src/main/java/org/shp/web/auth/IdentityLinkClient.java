package org.shp.web.auth;

import java.util.Optional;

/**
 * Nguồn liên kết: clinical đọc DB; service khác gọi HTTP nội bộ ({@link HttpIdentityLinkClient}).
 */
@FunctionalInterface
public interface IdentityLinkClient {
  /** Rỗng nếu chưa liên kết. */
  Optional<IdentityLink> find(String subject);
}
