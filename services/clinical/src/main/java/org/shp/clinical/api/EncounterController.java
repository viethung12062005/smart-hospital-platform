package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.r4.model.Resource;
import org.shp.authz.Principal;
import org.shp.clinical.app.EncounterService;
import org.shp.fhir.FhirJson;
import org.shp.fhir.search.SearchBundles;
import org.shp.fhir.search.SearchParser;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.shp.web.precondition.RequireIfMatch;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 09 C11 ({@code $start}), C12 (read, list by patient) and C13 (edit reason, note, diagnoses). The
 * Encounter is created from the check-in event (CLN-14); there is no public create (09 §3).
 */
@RestController
public class EncounterController {
  static final MediaType FHIR = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);
  static final Set<String> SEARCH_PARAMS = Set.of("patient", "_count");
  static final int DEFAULT_COUNT = 20;
  static final int MAX_COUNT = 100;

  private final EncounterService service;

  public EncounterController(EncounterService service) {
    this.service = service;
  }

  @PostMapping("/fhir/Encounter/{id}/$start")
  @RequireShpAuthz(resource = "Encounter", action = "update", context = "encounterFacts")
  public ResponseEntity<String> start(@PathVariable String id, HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    String practitionerId = p.roles().contains("doctor") ? p.fhirUserId() : null;
    return resource(service.start(id, practitionerId, changedBy(p)));
  }

  @GetMapping("/fhir/Encounter/{id}")
  @RequireShpAuthz(
      resource = "Encounter",
      action = "read",
      context = "encounterFacts",
      denyAsNotFound = true)
  public ResponseEntity<String> read(@PathVariable String id) {
    return resource(service.read(id));
  }

  /**
   * By patient only ({@code patient} is required): the matrix decides on that patient; a Patient
   * caller sees only finished visits (15 §3, AC "Patient sees only finished Encounter data").
   */
  @GetMapping("/fhir/Encounter")
  @RequireShpAuthz(resource = "Encounter", action = "read", context = "patientQueryFacts")
  public ResponseEntity<String> search(HttpServletRequest req) {
    Map<String, List<String>> params = SearchParser.fromServlet(req.getParameterMap());
    String patientId = ClinicalFacts.patientParam(req);
    boolean finishedOnly = IdentityInterceptor.principal(req).roles().contains("patient");
    var page = service.byPatient(patientId, finishedOnly, count(params));
    return bundle(req, params, page);
  }

  @PutMapping(
      path = "/fhir/Encounter/{id}",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireIfMatch
  @RequireShpAuthz(resource = "Encounter", action = "update", context = "encounterEditFacts")
  public ResponseEntity<String> update(
      @PathVariable String id, @RequestBody String body, HttpServletRequest req) {
    return resource(
        service.updateNote(
            id, body, expectedVersion(req), changedBy(IdentityInterceptor.principal(req))));
  }

  // ---------- helpers (shared with the other clinical FHIR controllers) ----------

  /** {@code _count} 1..100 (default 20); any parameter outside {@code allowed} → 400. */
  static int count(Map<String, List<String>> params) {
    return count(params, SEARCH_PARAMS);
  }

  static int count(Map<String, List<String>> params, Set<String> allowed) {
    for (String name : params.keySet()) {
      if (!allowed.contains(name)) {
        throw new ShpException("unsupported-parameter", name + " is not supported.");
      }
    }
    List<String> c = params.get("_count");
    if (c == null || c.isEmpty()) {
      return DEFAULT_COUNT;
    }
    try {
      int n = Integer.parseInt(c.get(0));
      if (n < 1 || n > MAX_COUNT) {
        throw new NumberFormatException();
      }
      return n;
    } catch (NumberFormatException e) {
      throw new ShpException("validation-failed", "_count must be between 1 and 100.");
    }
  }

  static ResponseEntity<String> bundle(
      HttpServletRequest req, Map<String, List<String>> params, List<? extends Resource> page) {
    String base = ServletUriComponentsBuilder.fromRequestUri(req).replaceQuery(null).toUriString();
    return ResponseEntity.ok()
        .contentType(FHIR)
        .body(FhirJson.serialize(SearchBundles.searchset(base, params, page, null)));
  }

  private static ResponseEntity<String> resource(EncounterService.View v) {
    return ResponseEntity.ok()
        .contentType(FHIR)
        .header(HttpHeaders.ETAG, ETags.format(v.version()))
        .body(FhirJson.serialize(v.resource()));
  }

  static String changedBy(Principal p) {
    return "User/" + p.subject();
  }

  static int expectedVersion(HttpServletRequest req) {
    long version = PreconditionInterceptor.expectedVersion(req);
    if (version > Integer.MAX_VALUE) {
      throw new ShpException("version-conflict");
    }
    return (int) version;
  }
}
