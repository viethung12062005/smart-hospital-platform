package org.shp.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.shp.web.errors.ShpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Look up the identity link with a cache. A found link is kept for 60 seconds (account lock takes
 * effect within ≤ 60 seconds, AC-08.1); "not linked" is kept for only 5 seconds so a newly
 * registered user (C1) can use it right away.
 *
 * <p>When the identity service (clinical) fails, a link seen in the last {@link #STALE_MAX} is
 * served from the cache instead (SHR-21: "on clinical failure use the cache"); with nothing usable
 * in the cache the request fails with 503 {@code dependency-unavailable}, never with a 500. {@code
 * STALE_MAX} equals the maximum access-token lifetime (NFR-SEC1), so an outage of clinical does not
 * keep a caller in for longer than their token would.
 */
public final class IdentityResolver {
  public static final Duration LINK_TTL = Duration.ofSeconds(60);
  public static final Duration MISS_TTL = Duration.ofSeconds(5);
  public static final Duration STALE_MAX = Duration.ofMinutes(15);
  private static final int MAX_ENTRIES = 10_000;
  private static final Logger log = LoggerFactory.getLogger(IdentityResolver.class);

  private record Entry(Optional<IdentityLink> link, Instant fetched, Instant expires) {}

  private final IdentityLinkClient client;
  private final Clock clock;
  private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

  public IdentityResolver(IdentityLinkClient client, Clock clock) {
    this.client = client;
    this.clock = clock;
  }

  public Optional<IdentityLink> resolve(String subject) {
    if (subject == null || subject.isEmpty()) {
      throw new IllegalArgumentException("subject is required");
    }
    Instant now = clock.instant();
    Entry hit = cache.get(subject);
    if (hit != null && hit.expires().isAfter(now)) {
      return hit.link();
    }
    Optional<IdentityLink> link;
    try {
      link = client.find(subject);
    } catch (RuntimeException e) {
      if (hit != null && hit.link().isPresent() && hit.fetched().plus(STALE_MAX).isAfter(now)) {
        // No subject or identifier in the log: the cause is enough to diagnose the outage.
        log.warn("Identity service unavailable, serving a cached link: {}", e.toString());
        return hit.link();
      }
      log.warn("Identity service unavailable and no cached link: {}", e.toString());
      throw new ShpException("dependency-unavailable");
    }
    if (cache.size() >= MAX_ENTRIES) {
      cache.clear(); // simple and safe: only costs a few extra lookups
    }
    cache.put(subject, new Entry(link, now, now.plus(link.isPresent() ? LINK_TTL : MISS_TTL)));
    return link;
  }
}
