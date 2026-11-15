package org.shp.web.auth;

import java.util.Optional;

/**
 * Source of links: clinical reads the DB; other services call the internal HTTP API ({@link
 * HttpIdentityLinkClient}).
 */
@FunctionalInterface
public interface IdentityLinkClient {
  /** Empty if not linked yet. */
  Optional<IdentityLink> find(String subject);
}
