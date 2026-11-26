package org.shp.scheduling.domain;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.shp.fhir.FhirJson;

/** Builds the FHIR Appointment stored in {@code appointment.content} (01-domain-model §4.5). */
public final class AppointmentFhir {
  public static final String SPECIALTY_SYSTEM = "https://shp.example.org/fhir/CodeSystem/specialty";
  public static final String BOOKING_CHANNEL_URL =
      "https://shp.example.org/fhir/StructureDefinition/booking-channel";

  private AppointmentFhir() {}

  /**
   * Reassigns the patient participant reference in a stored FHIR {@code Appointment} and updates
   * {@code meta.versionId} and {@code meta.lastUpdated}.
   */
  public static String reassignPatient(
      String content, UUID sourcePatientId, UUID targetPatientId, int versionId, Instant at) {
    var resource = FhirJson.parse(content, Appointment.class);
    resource.getMeta().setVersionId(Integer.toString(versionId));
    resource.getMeta().setLastUpdated(Date.from(at));
    String sourceRef = "Patient/" + sourcePatientId;
    String targetRef = "Patient/" + targetPatientId;
    for (var participant : resource.getParticipant()) {
      if (participant.hasActor() && sourceRef.equals(participant.getActor().getReference())) {
        participant.getActor().setReference(targetRef);
      }
    }
    return FhirJson.serialize(resource);
  }

  /**
   * The resource of a new booking: version 1 of {@code a}, created at {@code a.lastUpdated()}.
   *
   * @param comment optional patient note; left out of the resource when null or blank
   */
  public static String booked(AppointmentRecord a, String comment) {
    var resource = new Appointment();
    resource.setId(a.id().toString());
    resource.getMeta().setVersionId(Integer.toString(a.versionId()));
    resource.getMeta().setLastUpdated(Date.from(a.lastUpdated()));
    resource.setStatus(Appointment.AppointmentStatus.BOOKED);
    resource
        .addSpecialty()
        .addCoding(new Coding().setSystem(SPECIALTY_SYSTEM).setCode(a.specialty()));
    resource.setStart(Date.from(a.start()));
    resource.setEnd(Date.from(a.end()));
    resource.setCreated(Date.from(a.lastUpdated()));
    resource.addSlot(new Reference("Slot/" + a.slotId()));
    resource
        .addParticipant()
        .setActor(new Reference("Patient/" + a.patientId()))
        .setStatus(Appointment.ParticipationStatus.ACCEPTED);
    resource
        .addParticipant()
        .setActor(new Reference("Practitioner/" + a.practitionerId()))
        .setStatus(Appointment.ParticipationStatus.ACCEPTED);
    if (comment != null && !comment.isBlank()) {
      resource.setComment(comment);
    }
    resource.addExtension(new Extension(BOOKING_CHANNEL_URL, new CodeType(a.channel())));
    return FhirJson.serialize(resource);
  }
}
