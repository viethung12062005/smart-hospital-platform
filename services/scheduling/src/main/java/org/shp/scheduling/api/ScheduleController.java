package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Schedule;
import org.shp.authz.Principal;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.ScheduleService;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FHIR REST API controller for doctor work schedules (S1–S4). */
@RestController
public class ScheduleController {

  private static final MediaType FHIR_MEDIA_TYPE = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);

  /** Maximum {@code _count} per 07-api-conventions section 4. */
  private static final int MAX_COUNT = 100;

  private final ScheduleService service;

  public ScheduleController(ScheduleService service) {
    this.service = Objects.requireNonNull(service, "service must not be null");
  }

  /** S1: POST /fhir/Schedule - Create a doctor's work schedule. Permissions: Nurse, Admin. */
  @PostMapping(
      value = "/fhir/Schedule",
      consumes = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Schedule", action = "create")
  public ResponseEntity<String> createSchedule(
      @RequestBody String body, HttpServletRequest request) {
    Schedule schedule = FhirJson.parse(body, Schedule.class);
    Principal principal = IdentityInterceptor.principal(request);
    String changedBy = principal.subject() != null ? principal.subject() : "unknown";

    ScheduleRecord created = service.createSchedule(schedule, changedBy);
    String location = "/fhir/Schedule/" + created.id() + "/_history/" + created.versionId();

    return ResponseEntity.created(URI.create(location))
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(created.versionId()))
        .header(HttpHeaders.LOCATION, location)
        .body(created.content());
  }

  /**
   * S2: GET /fhir/Schedule/{id} - Read a schedule. Permissions: Nurse, Admin; Doctor (own schedule
   * only).
   */
  @GetMapping(
      value = "/fhir/Schedule/{id}",
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Schedule", action = "read", context = "scheduleFacts")
  public ResponseEntity<String> getSchedule(@PathVariable String id) {
    UUID uuid = parseUuid(id);
    ScheduleRecord record = service.getSchedule(uuid);

    return ResponseEntity.ok()
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(record.versionId()))
        .body(record.content());
  }

  /**
   * S3: GET /fhir/Schedule - Find schedules. Permissions: Nurse, Admin; Doctor (own schedule only).
   * If a doctor calls without their own actor, the server adds actor=&lt;self&gt;.
   */
  @GetMapping(
      value = "/fhir/Schedule",
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Schedule", action = "search")
  public ResponseEntity<String> searchSchedules(
      @RequestParam(value = "actor", required = false) String actor,
      @RequestParam(value = "specialty", required = false) String specialty,
      @RequestParam(value = "active", required = false) Boolean active,
      @RequestParam(value = "_count", required = false, defaultValue = "20") int count,
      @RequestParam(value = "_offset", required = false, defaultValue = "0") int offset,
      HttpServletRequest request) {
    if (count < 1 || count > MAX_COUNT || offset < 0) {
      throw new ShpException("validation-failed");
    }
    Principal principal = IdentityInterceptor.principal(request);
    UUID targetPractitionerId = null;

    if (principal.roles().contains("doctor")) {
      String doctorFhirUserId = principal.fhirUserId();
      if (doctorFhirUserId == null) {
        throw new ShpException("forbidden");
      }
      if (actor != null && !actor.isBlank()) {
        UUID requestedPractitionerId = ScheduleService.parsePractitionerId(actor);
        if (!requestedPractitionerId.toString().equalsIgnoreCase(doctorFhirUserId)) {
          throw new ShpException("forbidden");
        }
        targetPractitionerId = requestedPractitionerId;
      } else {
        targetPractitionerId = UUID.fromString(doctorFhirUserId);
      }
    } else {
      if (actor != null && !actor.isBlank()) {
        targetPractitionerId = ScheduleService.parsePractitionerId(actor);
      }
    }

    var records = service.searchSchedules(targetPractitionerId, specialty, active, count, offset);

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.SEARCHSET);
    bundle.setId(UUID.randomUUID().toString());
    for (var r : records) {
      Schedule s = FhirJson.parse(r.content(), Schedule.class);
      bundle.addEntry().setResource(s).setFullUrl("/fhir/Schedule/" + r.id());
    }

    return ResponseEntity.ok().contentType(FHIR_MEDIA_TYPE).body(FhirJson.serialize(bundle));
  }

  /**
   * S4: PUT /fhir/Schedule/{id} - Edit a schedule. Permissions: Nurse, Admin. Requires If-Match
   * header.
   */
  @PutMapping(
      value = "/fhir/Schedule/{id}",
      consumes = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Schedule", action = "update", context = "scheduleFacts")
  public ResponseEntity<String> updateSchedule(
      @PathVariable String id, @RequestBody String body, HttpServletRequest request) {
    UUID uuid = parseUuid(id);
    long expectedVersion = PreconditionInterceptor.expectedVersion(request);
    Schedule schedule = FhirJson.parse(body, Schedule.class);

    Principal principal = IdentityInterceptor.principal(request);
    String changedBy = principal.subject() != null ? principal.subject() : "unknown";

    ScheduleRecord updated = service.updateSchedule(uuid, schedule, expectedVersion, changedBy);
    String location = "/fhir/Schedule/" + updated.id() + "/_history/" + updated.versionId();

    return ResponseEntity.ok()
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(updated.versionId()))
        .header(HttpHeaders.LOCATION, location)
        .body(updated.content());
  }

  private static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ShpException("not-found");
    }
  }
}
