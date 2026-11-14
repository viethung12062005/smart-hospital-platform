package org.shp.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Look up the identity link with a cache. A found link is kept for 60 seconds (account lock takes
 * effect within ≤ 60 seconds, AC-08.1); "not linked" is kept for only 5 seconds so a newly
 * registered user (C1) can use it right away.
 */
public final class IdentityResolver {
  public static final Duration LINK_TTL = Duration.ofSeconds(60);
  public static final Duration MISS_TTL = Duration.ofSeconds(5);
  private static final int MAX_ENTRIES = 10_000;

  private record Entry(Optional<IdentityLink> link, Instant expires) {}

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
    Optional<IdentityLink> link = client.find(subject);
    if (cache.size() >= MAX_ENTRIES) {
      cache.clear(); // simple and safe: only costs a few extra lookups
    }
    cache.put(subject, new Entry(link, now.plus(link.isPresent() ? LINK_TTL : MISS_TTL)));
    return link;
  }
}
