package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.shp.authz.Principal;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.CancellationService;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.shp.web.precondition.RequireIdempotencyKey;
import org.shp.web.precondition.RequireIfMatch;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** S14 {@code POST /fhir/Appointment/{id}/$cancel}: cancel a booked appointment (BR-02). */
@RestController
public class CancelController {
  private static final MediaType FHIR_MEDIA_TYPE = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);

  private final CancellationService service;

  public CancelController(CancellationService service) {
    this.service = Objects.requireNonNull(service, "service must not be null");
  }

  /**
   * 200 with the cancelled Appointment. A patient cancels their own (or a linked profile's)
   * appointment at least 2 hours before it starts; a nurse may always cancel.
   */
  @PostMapping(
      value = "/fhir/Appointment/{id}/$cancel",
      consumes = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireIdempotencyKey
  @RequireIfMatch
  @RequireShpAuthz(resource = "Appointment", action = "cancel", context = "appointmentCancelFacts")
  public ResponseEntity<String> cancel(@PathVariable String id, HttpServletRequest request) {
    Principal principal = IdentityInterceptor.principal(request);
    CancelRequest body = CancelRequest.from(request);
    UUID uuid;
    try {
      uuid = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ShpException("not-found");
    }
    var result =
        service.cancel(
            principal.subject(),
            PreconditionInterceptor.idempotencyKey(request),
            body.hash(),
            uuid,
            PreconditionInterceptor.expectedVersion(request),
            body.reason(),
            principal.roles().contains("nurse"));
    var a = result.appointment();
    return ResponseEntity.ok()
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(a.versionId()))
        .body(a.content());
  }
}
