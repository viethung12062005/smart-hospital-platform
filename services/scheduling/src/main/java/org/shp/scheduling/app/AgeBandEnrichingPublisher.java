package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.shp.events.CloudEvent;
import org.shp.events.EventPublisher;
import org.shp.scheduling.domain.AgeBandClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adds {@code ageBand} to {@code appointment-booked} at publish time (13 §3, E1), so booking never
 * waits for clinical. Looks the band up in clinical and keeps it for 24 hours; when clinical fails,
 * times out or does not know the patient, the event carries {@code unknown} and is still published
 * (nothing is cached for such a failure, so the next event tries again).
 */
public final class AgeBandEnrichingPublisher implements EventPublisher {
  static final String BOOKED = "shp.scheduling.appointment-booked.v1";
  static final String UNKNOWN = "unknown";
  static final Duration TTL = Duration.ofHours(24);
  private static final int MAX_ENTRIES = 10_000;
  private static final Logger log = LoggerFactory.getLogger(AgeBandEnrichingPublisher.class);

  private record Cached(String band, Instant expiresAt) {}

  private final EventPublisher delegate;
  private final AgeBandClient client;
  private final Clock clock;
  private final Map<UUID, Cached> cache =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Cached> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  public AgeBandEnrichingPublisher(EventPublisher delegate, AgeBandClient client, Clock clock) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public void publish(CloudEvent event) {
    if (!BOOKED.equals(event.type()) || !(event.data() instanceof ObjectNode data)) {
      delegate.publish(event);
      return;
    }
    ObjectNode enriched = data.deepCopy();
    if (!enriched.hasNonNull("ageBand")) {
      enriched.put("ageBand", band(data.path("patientId")));
    }
    delegate.publish(
        new CloudEvent(
            event.specVersion(),
            event.id(),
            event.source(),
            event.type(),
            event.subject(),
            event.time(),
            event.dataContentType(),
            event.dataSchema(),
            event.traceParent(),
            (JsonNode) enriched));
  }

  private String band(JsonNode patientId) {
    UUID id;
    try {
      id = UUID.fromString(patientId.asString(""));
    } catch (IllegalArgumentException e) {
      return UNKNOWN;
    }
    Instant now = clock.instant();
    synchronized (cache) {
      Cached hit = cache.get(id);
      if (hit != null && hit.expiresAt().isAfter(now)) {
        return hit.band();
      }
    }
    try {
      var found = client.ageBand(id);
      if (found.isEmpty()) {
        return UNKNOWN;
      }
      synchronized (cache) {
        cache.put(id, new Cached(found.get(), now.plus(TTL)));
      }
      return found.get();
    } catch (RuntimeException e) {
      log.warn(
          "Age band lookup failed, publishing with ageBand=unknown: {}",
          e.getClass().getSimpleName());
      return UNKNOWN;
    }
  }
}
