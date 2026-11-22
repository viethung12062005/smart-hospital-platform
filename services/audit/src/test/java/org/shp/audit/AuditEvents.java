package org.shp.audit;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.shp.events.CloudEvents;
import org.shp.events.audit.AuditRecord;

/** Synthetic {@code shp.audit.recorded.v1} events for tests. */
public final class AuditEvents {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-18T10:15:30Z"), ZoneOffset.UTC);

  private AuditEvents() {}

  public static AuditRecord record(UUID eventId) {
    return new AuditRecord(
        eventId,
        Instant.parse("2026-11-18T10:15:00Z"),
        "clinical",
        "read",
        "success",
        new AuditRecord.Actor("kc-doctor-demo", "doctor", null),
        null,
        null,
        UUID.fromString("0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02"),
        "Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e06",
        "TREAT",
        null,
        "4bf92f3577b34da6a3ce929d0e0e4736",
        new AuditRecord.Client("10.1.2.x", "test-agent"));
  }

  public static String json(UUID eventId) {
    return CloudEvents.toJson(record(eventId).toCloudEvent(CLOCK));
  }

  public static byte[] bytes(UUID eventId) {
    return json(eventId).getBytes(StandardCharsets.UTF_8);
  }
}
