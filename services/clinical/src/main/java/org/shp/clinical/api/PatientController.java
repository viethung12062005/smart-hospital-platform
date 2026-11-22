package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import org.shp.clinical.app.PatientService;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.fhir.FhirJson;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.AuthzInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** C4 read, C5 update and C7 history of a Patient (09 §2). */
@RestController
class PatientController {
  private static final MediaType FHIR_JSON = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);

  private final PatientService service;

  PatientController(PatientService service) {
    this.service = service;
  }

  @GetMapping("/fhir/Patient/{id}")
  @RequireShpAuthz(resource = "Patient", action = "read", context = "patientFacts")
  ResponseEntity<String> read(@PathVariable String id) {
    return resource(service.read(id));
  }

  @PutMapping("/fhir/Patient/{id}")
  @RequireShpAuthz(resource = "Patient", action = "update", context = "patientFacts")
  ResponseEntity<String> update(
      @PathVariable String id, @RequestBody String body, HttpServletRequest req) {
    Entry updated =
        service.update(
            IdentityInterceptor.principal(req),
            id,
            PreconditionInterceptor.expectedVersion(req),
            body,
            AuthzInterceptor.decision(req),
            req.getRemoteAddr());
    return resource(updated);
  }

  /** Nurses only; see {@code PatientApiConfig}. */
  @GetMapping("/fhir/Patient/{id}/_history")
  @RequireShpAuthz(resource = "Patient", action = "read", context = "patientHistoryFacts")
  ResponseEntity<String> history(@PathVariable String id) {
    return ResponseEntity.ok().contentType(FHIR_JSON).body(FhirJson.serialize(service.history(id)));
  }

  private static ResponseEntity<String> resource(Entry entry) {
    return ResponseEntity.ok()
        .contentType(FHIR_JSON)
        .header(HttpHeaders.ETAG, ETags.format(entry.versionId()))
        .body(FhirJson.serialize(PatientRepository.toFhir(entry)));
  }
}
