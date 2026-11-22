package org.shp.notification.api;

import java.util.List;
import java.util.UUID;
import org.shp.notification.app.ReminderManagementService;
import org.shp.notification.app.ReminderManagementService.DeadLetterView;
import org.shp.notification.app.ReminderManagementService.ReminderView;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification reminder management API (N5-N6, US-044).
 *
 * <p>Responses use explicit DTOs and never expose patient contact information or raw MongoDB
 * documents.
 *
 * <p>Authorization resources must be added to the shared authorization matrix before these
 * endpoints can be used.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class ReminderManagementController {

  private final ReminderManagementService service;

  public ReminderManagementController(ReminderManagementService service) {
    this.service = service;
  }

  /**
   * N5: View reminders for an appointment.
   *
   * <p>Permitted roles: Nurse and Admin.
   */
  @GetMapping("/reminders")
  @RequireShpAuthz(resource = "NotificationReminder", action = "read")
  public List<ReminderView> reminders(
      @RequestParam(name = "appointment", required = false) String appointmentId) {

    if (appointmentId == null || appointmentId.isBlank()) {
      throw new ShpException("validation-failed", "Appointment ID is required");
    }

    try {
      UUID.fromString(appointmentId);
    } catch (IllegalArgumentException exception) {
      throw new ShpException("validation-failed", "Invalid appointment ID");
    }

    return service.remindersForAppointment(appointmentId);
  }

  /**
   * N6: List permanently failed reminders.
   *
   * <p>Permitted role: Admin only.
   */
  @GetMapping("/dead-letters")
  @RequireShpAuthz(resource = "NotificationDeadLetter", action = "read")
  public List<DeadLetterView> deadLetters() {
    return service.deadLetters();
  }
}
