package org.shp.notification.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.shp.notification.infra.MongoReminderManagementStore;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

@Service
public class ReminderManagementService {

  private static final int MAX_DEAD_LETTERS = 100;

  private final MongoReminderManagementStore store;

  public ReminderManagementService(MongoReminderManagementStore store) {
    this.store = store;
  }

  public record ReminderView(
      String reminderId, String kind, String subject, String status, Instant dueAt, int attempts) {}

  public record DeadLetterView(
      String reminderId,
      String kind,
      String subject,
      int attempts,
      String lastError,
      Instant deadLetteredAt) {}

  /** N5: read reminders for an appointment without exposing patient contact data. */
  public List<ReminderView> remindersForAppointment(String appointmentId) {
    return store.findByAppointment(appointmentId).stream()
        .map(d ->
            new ReminderView(
                d.getString("_id"),
                d.getString("kind"),
                d.getString("subjectRef"),
                d.getString("status"),
                instant(d, "dueAt"),
                d.getInteger("attempts", 0)))
        .toList();
  }

  /** N6: read dead-lettered reminders without exposing delivery identifiers. */
  public List<DeadLetterView> deadLetters() {
    return store.findDeadLetters(MAX_DEAD_LETTERS).stream()
        .map(d ->
            new DeadLetterView(
                d.getString("_id"),
                d.getString("kind"),
                d.getString("subjectRef"),
                d.getInteger("attempts", 0),
                lastError(d),
                instant(d, "completedAt")))
        .toList();
  }

  private static Instant instant(Document document, String field) {
    var value = document.getDate(field);
    return value == null ? null : value.toInstant();
  }

  private static String lastError(Document reminder) {
    List<Document> deliveries = reminder.getList("deliveries", Document.class);
    if (deliveries == null) {
      return null;
    }

    for (int i = deliveries.size() - 1; i >= 0; i--) {
      String code = deliveries.get(i).getString("errorCode");
      if (code != null && !code.isBlank()) {
        return code;
      }
    }

    return null;
  }

  /**
   * Internal retry operation.
   *
   * <p>Must not be exposed via HTTP until appointment cancellation has been checked through an
   * authoritative source.
   */
  public void retryDeadLetter(String reminderId, Clock clock) {
    if (reminderId == null || reminderId.isBlank()) {
      throw new ShpException("not-found");
    }

    if (!store.retryDeadLetter(reminderId, clock.instant())) {
      throw new ShpException("invalid-state-transition");
    }
  }
}
