package org.shp.clinical.app;

import java.time.Instant;
import java.util.Set;
import org.shp.clinical.domain.CareRelationship;
import org.shp.clinical.domain.CareRelationship.Source;
import org.shp.events.CloudEvent;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Keeps {@code care_relationships} up to date from the appointment events (13 §3 E1, E2, E3, E5; 15
 * §4). Each handler runs once per event inside the inbox transaction (SHR-08); a duplicate or an
 * older version of the same appointment changes nothing.
 */
@Service
public class CareRelationshipService {
  public static final String CONSUMER = "clinical";
  public static final String BOOKED = "shp.scheduling.appointment-booked.v1";
  public static final String CANCELLED = "shp.scheduling.appointment-cancelled.v1";
  public static final String RESCHEDULED = "shp.scheduling.appointment-rescheduled.v1";
  public static final String STATUS_CHANGED = "shp.scheduling.appointment-status-changed.v1";
  public static final Set<String> TYPES = Set.of(BOOKED, CANCELLED, RESCHEDULED, STATUS_CHANGED);

  private final CareRelationships store;

  public CareRelationshipService(CareRelationships store) {
    this.store = store;
  }

  /** Routes an event of {@link #TYPES} to its handler. */
  public void handle(CloudEvent event) {
    JsonNode data = event.data();
    switch (event.type()) {
      case BOOKED -> onBooked(data);
      case CANCELLED -> onCancelled(data);
      case RESCHEDULED -> onRescheduled(data);
      case STATUS_CHANGED -> onStatusChanged(data);
      default -> throw new IllegalArgumentException("not a care event: " + event.type());
    }
  }

  /** E1: the doctor has care until {@code start + 365 days}. */
  void onBooked(JsonNode data) {
    addAppointment(
        text(data, "practitionerId"),
        text(data, "patientId"),
        text(data, "appointmentId"),
        Instant.parse(text(data, "start")),
        version(data));
  }

  /** E2: the appointment no longer gives care; other sources keep the relationship. */
  void onCancelled(JsonNode data) {
    String practitionerId = text(data, "practitionerId");
    String patientId = text(data, "patientId");
    String appointmentId = text(data, "appointmentId");
    long version = version(data);
    var current = store.find(practitionerId, patientId);
    if (current.isEmpty()) {
      return; // booked event not seen (or nothing to remove): no care to take away
    }
    var next =
        current.get().withStatus(CareRelationship.APPOINTMENT, appointmentId, "cancelled", version);
    saveIfChanged(current.get(), next);
  }

  /** E3: one event = cancel the old appointment + book the new one (13 §3). */
  void onRescheduled(JsonNode data) {
    String practitionerId = text(data, "practitionerId");
    String patientId = text(data, "patientId");
    var current =
        store
            .find(practitionerId, patientId)
            .orElse(CareRelationship.empty(practitionerId, patientId));
    var next =
        current
            .ended(CareRelationship.APPOINTMENT, text(data, "oldAppointmentId"), "cancelled")
            .with(
                new Source(
                    CareRelationship.APPOINTMENT,
                    text(data, "newAppointmentId"),
                    "booked",
                    Instant.parse(text(data, "newStart")),
                    version(data)));
    saveIfChanged(current, next);
  }

  /**
   * E5: {@code noshow} ends the appointment source like a cancellation (the visit never happened);
   * {@code fulfilled} keeps it. E5 has no {@code practitionerId}, so the relationship is found by
   * the patient and the appointment.
   */
  void onStatusChanged(JsonNode data) {
    String patientId = text(data, "patientId");
    String appointmentId = text(data, "appointmentId");
    String status = text(data, "newStatus");
    long version = version(data);
    for (CareRelationship current :
        store.findBySource(patientId, CareRelationship.APPOINTMENT, appointmentId)) {
      saveIfChanged(
          current,
          current.withStatus(CareRelationship.APPOINTMENT, appointmentId, status, version));
    }
  }

  /** Adds or refreshes an appointment source (also used for the new appointment of E3). */
  private void addAppointment(
      String practitionerId, String patientId, String appointmentId, Instant start, long version) {
    var current =
        store
            .find(practitionerId, patientId)
            .orElse(CareRelationship.empty(practitionerId, patientId));
    saveIfChanged(
        current,
        current.with(
            new Source(CareRelationship.APPOINTMENT, appointmentId, "booked", start, version)));
  }

  private void saveIfChanged(CareRelationship before, CareRelationship after) {
    if (!after.equals(before)) {
      store.save(after);
    }
  }

  private static String text(JsonNode data, String field) {
    JsonNode value = data.get(field);
    if (value == null || value.isNull()) {
      // The schema check of EventConsumers runs first, so this means a contract mismatch.
      throw new IllegalArgumentException("Event data is missing " + field);
    }
    return value.asString();
  }

  private static long version(JsonNode data) {
    JsonNode value = data.get("version");
    if (value == null || !value.isNumber()) {
      throw new IllegalArgumentException("Event data is missing version");
    }
    return value.asLong();
  }
}
