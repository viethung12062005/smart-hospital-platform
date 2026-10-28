package org.shp.events.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;

/**
 * Bản ghi audit — payload của event {@code shp.audit.recorded.v1}. KHÔNG chứa nội dung lâm sàng,
 * tên, SĐT hay giá trị tìm kiếm. Dựng bằng {@link AuditRecordBuilder}.
 */
public record AuditRecord(
    UUID eventId,
    Instant occurredAt,
    String service,
    String action,
    String outcome,
    Actor actor,
    UUID patientId,
    String resource,
    String purposeOfUse,
    String reason,
    String traceId,
    Client client) {

  public record Actor(String subject, String role, String fhirUser) {}

  public record Client(String ip, String userAgent) {}

  /** Đóng gói thành CloudEvent E11 (subject = {@code Audit/{eventId}}). */
  public CloudEvent toCloudEvent(Clock clock) {
    return CloudEvents.create(
        service, "shp.audit.recorded.v1", "Audit/" + eventId, this, clock, eventId);
  }
}
