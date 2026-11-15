package org.shp.notification.domain;

import java.time.Instant;
import java.util.List;

/**
 * One reminder document (04-db-notification-ml §1.2). Delivery attempts are embedded; the unique
 * {@code (subjectRef, kind)} index keeps a repeated event from creating a second reminder.
 */
public record Reminder(
    String id,
    String subjectRef,
    String patientId,
    ReminderKind kind,
    Instant appointmentStart,
    Instant dueAt,
    String status,
    int attempts,
    Instant lockedUntil,
    List<Delivery> deliveries,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt) {

  public static final String STATUS_SCHEDULED = "scheduled";

  public Reminder {
    deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
  }

  /** One embedded delivery attempt (§1.2). */
  public record Delivery(
      int attemptNo,
      String channel,
      String provider,
      String outcome,
      String providerMessageId,
      String errorCode,
      Long latencyMs,
      Instant attemptedAt) {}
}
