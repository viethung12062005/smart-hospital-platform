package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.shp.authz.Principal;
import org.shp.clinical.app.CounterRegistrationService;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.fhir.FhirJson;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 09 C3 {@code POST /fhir/Patient}: a nurse creates a record at the counter (matrix {@code
 * PAT-CREATE-NUR}); 201 with the {@code Patient} and its {@code identifier[mrn]}. {@code
 * ?forceCreate=true} with {@code X-Duplicate-Override-Reason} overrides a suspected duplicate.
 */
@RestController
public class CounterRegistrationController {
  static final MediaType FHIR = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);
  public static final String OVERRIDE_HEADER = "X-Duplicate-Override-Reason";

  private final CounterRegistrationService service;

  public CounterRegistrationController(CounterRegistrationService service) {
    this.service = service;
  }

  @PostMapping(
      path = "/fhir/Patient",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireShpAuthz(resource = "Patient", action = "create")
  public ResponseEntity<String> create(
      @RequestBody String body,
      @RequestParam(name = "forceCreate", defaultValue = "false") boolean forceCreate,
      @RequestHeader(name = OVERRIDE_HEADER, required = false) String reason,
      HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    // The matrix also lets a patient create a Patient (PAT-CREATE-PAT, for self-registration C1);
    // C3 is the counter path and is for nurses only.
    if (!p.roles().contains("nurse")) {
      throw new ShpException("forbidden", "Only a nurse creates a record at the counter.");
    }
    Entry created =
        service.create(
            body,
            forceCreate,
            reason,
            new CounterRegistrationService.Nurse(p.subject(), p.fhirUserId()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .contentType(FHIR)
        .header(HttpHeaders.ETAG, ETags.format(created.versionId()))
        .location(URI.create("/fhir/Patient/" + created.id() + "/_history/" + created.versionId()))
        .body(FhirJson.serialize(PatientRepository.toFhir(created)));
  }
}
