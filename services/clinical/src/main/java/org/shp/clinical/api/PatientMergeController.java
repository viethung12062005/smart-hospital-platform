package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import org.shp.authz.Principal;
import org.shp.clinical.app.PatientMergeService;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.events.CloudEvents;
import org.shp.fhir.FhirJson;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 09 C8 {@code POST /fhir/Patient/{id}/$merge} with {@code { "target": { "reference": "Patient/..."
 * } }}: nurses only, authorized by the matrix rule {@code PAT-MERGE-NUR} ({@code Patient: merge});
 * a patient may update their own record but never merge.
 */
@RestController
public class PatientMergeController {
  static final MediaType FHIR = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);

  private final PatientMergeService service;

  public PatientMergeController(PatientMergeService service) {
    this.service = service;
  }

  @PostMapping(
      path = "/fhir/Patient/{id}/$merge",
      consumes = {"application/json", FhirJson.MEDIA_TYPE})
  @RequireShpAuthz(resource = "Patient", action = "merge", context = "patientFacts")
  public ResponseEntity<String> merge(
      @PathVariable String id, @RequestBody String body, HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    Entry merged = service.merge(id, target(body), "User/" + p.subject());
    return ResponseEntity.ok()
        .contentType(FHIR)
        .header(HttpHeaders.ETAG, ETags.format(merged.versionId()))
        .body(FhirJson.serialize(PatientRepository.toFhir(merged)));
  }

  /** {@code target.reference} of the body. */
  static String target(String body) {
    try {
      JsonNode ref = CloudEvents.JSON.readTree(body).path("target").path("reference");
      return ref.isString() ? ref.asString() : null;
    } catch (RuntimeException e) {
      throw new ShpException(
          "validation-failed", "The body must be { \"target\": Reference(Patient) }.");
    }
  }
}
