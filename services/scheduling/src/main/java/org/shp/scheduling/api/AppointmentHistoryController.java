package org.shp.scheduling.api;

import java.util.Objects;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.AppointmentHistoryService;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** S17 {@code GET /fhir/Appointment/{id}/_history}: retrieve all versions of an Appointment. */
@RestController
public class AppointmentHistoryController {

  private static final MediaType FHIR_MEDIA_TYPE = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);

  private final AppointmentHistoryService service;

  public AppointmentHistoryController(AppointmentHistoryService service) {
    this.service = Objects.requireNonNull(service, "service must not be null");
  }

  /**
   * S17: return all stored versions of an appointment as a FHIR R4 {@code history} Bundle.
   * Permissions: Nurse and Admin only (08-api-scheduling §1).
   */
  @GetMapping(
      value = "/fhir/Appointment/{id}/_history",
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(
      resource = "AppointmentHistory",
      action = "read",
      context = "appointmentHistoryFacts")
  public ResponseEntity<String> getHistory(@PathVariable String id) {
    UUID appointmentId = parseUuid(id);
    Bundle bundle = service.getHistory(appointmentId);
    return ResponseEntity.ok().contentType(FHIR_MEDIA_TYPE).body(FhirJson.serialize(bundle));
  }

  private static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ShpException("not-found");
    }
  }
}
