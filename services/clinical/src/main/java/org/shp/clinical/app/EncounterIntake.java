package org.shp.clinical.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.shp.clinical.domain.CareRelationship;
import org.shp.clinical.domain.CareRelationship.Source;
import org.shp.events.CloudEvent;
import org.shp.events.Uuids;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * E4 {@code appointment-checked-in} (13 §3): creates the {@code arrived} Encounter of the
 * appointment (01 §4.6) and gives the attending doctor an {@code encounter} care source (15 §4).
 * One Encounter per appointment: a second check-in event for the same appointment changes nothing
 * (unique {@code search.appointmentId}, 03 §2.5).
 */
@Service
public class EncounterIntake {
  public static final String CHECKED_IN = "shp.scheduling.appointment-checked-in.v1";

  /** {@code changedBy} of the history entry: the event, not a user, created it. */
  static final String CHANGED_BY = "system/appointment-checked-in";

  static final String ACT_CODE = "http://terminology.hl7.org/CodeSystem/v3-ActCode";
  static final String PARTICIPATION = "http://terminology.hl7.org/CodeSystem/v3-ParticipationType";
  static final String SPECIALTY = "https://shp.example.org/fhir/CodeSystem/specialty";

  private final Encounters encounters;
  private final CareRelationships care;
  private final Clock clock;

  public EncounterIntake(Encounters encounters, CareRelationships care, Clock clock) {
    this.encounters = encounters;
    this.care = care;
    this.clock = clock;
  }

  public void onCheckedIn(CloudEvent event) {
    JsonNode data = event.data();
    String appointmentId = text(data, "appointmentId");
    if (encounters.existsForAppointment(appointmentId)) {
      return; // already created by an earlier delivery or a republished event
    }
    String patientId = text(data, "patientId");
    String practitionerId = text(data, "practitionerId");
    String specialty = text(data, "specialty");
    Instant checkedInAt = Instant.parse(text(data, "checkedInAt"));
    String id = Uuids.v7(clock).toString();

    encounters.create(
        id,
        patientId,
        resource(id, appointmentId, patientId, practitionerId, specialty, checkedInAt),
        search(appointmentId, practitionerId, specialty, checkedInAt),
        CHANGED_BY);

    var relationship =
        care.find(practitionerId, patientId)
            .orElse(CareRelationship.empty(practitionerId, patientId));
    care.save(
        relationship.with(new Source(CareRelationship.ENCOUNTER, id, "arrived", checkedInAt, 1)));
  }

  /** The {@code arrived} Encounter of 01 §4.6; {@code period.end} is set at {@code $finish}. */
  static Document resource(
      String id,
      String appointmentId,
      String patientId,
      String practitionerId,
      String specialty,
      Instant checkedInAt) {
    return new Document("resourceType", "Encounter")
        .append("id", id)
        .append("status", "arrived")
        .append("class", new Document("system", ACT_CODE).append("code", "AMB"))
        .append(
            "serviceType",
            new Document(
                "coding", List.of(new Document("system", SPECIALTY).append("code", specialty))))
        .append("subject", new Document("reference", "Patient/" + patientId))
        .append(
            "participant",
            List.of(
                new Document(
                        "type",
                        List.of(
                            new Document(
                                "coding",
                                List.of(
                                    new Document("system", PARTICIPATION).append("code", "ATND")))))
                    .append(
                        "individual", new Document("reference", "Practitioner/" + practitionerId))))
        .append("appointment", List.of(new Document("reference", "Appointment/" + appointmentId)))
        .append("period", new Document("start", checkedInAt.toString()));
  }

  /** 03 §2.2 search fields of {@code encounters}. */
  static Document search(
      String appointmentId, String practitionerId, String specialty, Instant checkedInAt) {
    return new Document("status", "arrived")
        .append("practitionerId", practitionerId)
        .append("appointmentId", appointmentId)
        .append("periodStart", Date.from(checkedInAt))
        .append("periodEnd", null)
        .append("serviceType", specialty);
  }

  private static String text(JsonNode data, String field) {
    JsonNode value = data.get(field);
    if (value == null || value.isNull()) {
      // The schema check of EventConsumers runs first, so this means a contract mismatch.
      throw new IllegalArgumentException("Event data is missing " + field);
    }
    return value.asString();
  }
}
