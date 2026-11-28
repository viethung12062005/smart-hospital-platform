package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.shp.authz.Principal;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.CheckInService;
import org.shp.scheduling.domain.AppointmentRecord;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.shp.web.precondition.RequireIfMatch;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S16 {@code POST /fhir/Appointment/{id}/$check-in}: check in a booked appointment (Nurse only).
 */
@RestController
public class CheckInController {
  private static final MediaType FHIR_MEDIA_TYPE = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);

  private final CheckInService service;

  public CheckInController(CheckInService service) {
    this.service = Objects.requireNonNull(service, "service must not be null");
  }

  /**
   * 200 OK with the updated {@code Appointment} ({@code status=arrived}, updated {@code ETag}).
   * Nurse only; {@code If-Match} required; check-in allowed within 60 minutes before to 30 minutes
   * after the appointment start time.
   */
  @PostMapping(
      value = "/fhir/Appointment/{id}/$check-in",
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireIfMatch
  @RequireShpAuthz(
      resource = "Appointment",
      action = "check-in",
      context = "appointmentCheckInFacts")
  public ResponseEntity<String> checkIn(@PathVariable String id, HttpServletRequest request) {
    UUID appointmentId = parseUuidOrNotFound(id);
    long expectedVersion = PreconditionInterceptor.expectedVersion(request);
    Principal principal = IdentityInterceptor.principal(request);
    AppointmentRecord updated =
        service.checkIn(appointmentId, expectedVersion, principal.subject());
    return ResponseEntity.ok()
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(updated.versionId()))
        .body(updated.content());
  }

  private static UUID parseUuidOrNotFound(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ShpException("not-found");
    }
  }
}
