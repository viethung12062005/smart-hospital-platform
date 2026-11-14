package org.shp.events.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;

/**
 * Audit record — payload of the {@code shp.audit.recorded.v1} event. Contains NO clinical content,
 * names, phone numbers or search values. Build with {@link AuditRecordBuilder}.
 */
public record AuditRecord(
    UUID eventId,
    Instant occurredAt,
    String service,
    String action,
    String outcome,
    Actor actor,
    String onBehalfOf,
    String relationship,
    UUID patientId,
    String resource,
    String purposeOfUse,
    String reason,
    String traceId,
    Client client) {

  public record Actor(String subject, String role, String fhirUser) {}

  public record Client(String ip, String userAgent) {}

  /** Wrap as an E11 CloudEvent (subject = {@code Audit/{eventId}}). */
  public CloudEvent toCloudEvent(Clock clock) {
    return CloudEvents.create(
        service, "shp.audit.recorded.v1", "Audit/" + eventId, this, clock, eventId);
  }
}
