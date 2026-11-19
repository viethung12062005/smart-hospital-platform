package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.r4.model.Condition;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.clinical.app.ClinicalReads;
import org.shp.clinical.app.ConditionService;
import org.shp.clinical.app.Conditions;
import org.shp.clinical.app.Encounters;
import org.shp.fhir.FhirJson;
import org.shp.fhir.search.SearchParser;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ErrorWriter;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.RequireIfMatch;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 09 C17 Condition: a doctor with care records (POST) and corrects (PUT, e.g. {@code
 * entered-in-error}) a diagnosis; read and list follow the Observation read rules (15 §3).
 */
@RestController
public class ConditionController {
  static final Set<String> SEARCH_PARAMS = Set.of("patient", "_count");

  private final ConditionService service;

  public ConditionController(ConditionService service) {
    this.service = service;
  }

  @PostMapping(
      path = "/fhir/Condition",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireShpAuthz(resource = "Condition", action = "create", context = "conditionCreateFacts")
  public ResponseEntity<String> create(HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    var v =
        service.create(RequestBodies.read(req), p.fhirUserId(), EncounterController.changedBy(p));
    String id = v.resource().getIdElement().getIdPart();
    return ResponseEntity.status(HttpStatus.CREATED)
        .contentType(EncounterController.FHIR)
        .header(HttpHeaders.ETAG, ETags.format(v.version()))
        .location(URI.create("/fhir/Condition/" + id + "/_history/" + v.version()))
        .body(FhirJson.serialize(v.resource()));
  }

  @PutMapping(
      path = "/fhir/Condition/{id}",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireIfMatch
  @RequireShpAuthz(resource = "Condition", action = "update", context = "conditionFacts")
  public ResponseEntity<String> update(
      @PathVariable String id, @RequestBody String body, HttpServletRequest req) {
    var v =
        service.update(
            id,
            body,
            EncounterController.expectedVersion(req),
            EncounterController.changedBy(IdentityInterceptor.principal(req)));
    return ok(v);
  }

  @GetMapping("/fhir/Condition/{id}")
  @RequireShpAuthz(
      resource = "Condition",
      action = "read",
      context = "conditionFacts",
      denyAsNotFound = true)
  public ResponseEntity<String> read(@PathVariable String id) {
    return ok(service.read(id));
  }

  @GetMapping("/fhir/Condition")
  @RequireShpAuthz(resource = "Condition", action = "read", context = "patientQueryFacts")
  public ResponseEntity<String> search(HttpServletRequest req) {
    Map<String, List<String>> params = SearchParser.fromServlet(req.getParameterMap());
    int count = EncounterController.count(params, SEARCH_PARAMS);
    String patientId = ClinicalFacts.searchPatient(req, IdentityInterceptor.principal(req));
    boolean patientCaller = IdentityInterceptor.principal(req).roles().contains("patient");
    List<Condition> page = service.byPatient(patientId, patientCaller, count);
    return EncounterController.bundle(req, params, page);
  }

  /** US-026 AC-3: 422 {@code invalid-code}; the detail names the field only. */
  @ExceptionHandler(ConditionService.InvalidCodeException.class)
  void invalidCode(
      ConditionService.InvalidCodeException ex, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    ErrorWriter.write(req, res, ConditionService.INVALID_CODE, ex.getMessage(), null);
  }

  private static ResponseEntity<String> ok(ConditionService.View v) {
    return ResponseEntity.ok()
        .contentType(EncounterController.FHIR)
        .header(HttpHeaders.ETAG, ETags.format(v.version()))
        .body(FhirJson.serialize(v.resource()));
  }

  /** Matrix facts of the Condition endpoints (15 §3, §4). */
  @Configuration
  static class Facts {

    /** Create: the patient is the {@code subject} of the body (read once, see RequestBodies). */
    @Bean
    ResourceContextResolver conditionCreateFacts(ClinicalReads reads, Clock clock) {
      return (req, vars, principal) -> {
        Condition c;
        try {
          c = FhirJson.parse(RequestBodies.read(req), Condition.class);
        } catch (ShpException e) {
          throw e;
        } catch (RuntimeException e) {
          throw new ShpException("validation-failed", "The body is not a Condition.");
        }
        String ref = c.getSubject().getReference();
        if (ref == null || !ref.matches("^Patient/[A-Za-z0-9-]{1,64}$")) {
          throw new ShpException(
              "validation-failed", "Condition.subject must reference Patient/{id}.");
        }
        String patientId = ref.substring("Patient/".length());
        return care(
                ResourceContext.builder().patientId(patientId), patientId, principal, reads, clock)
            .build();
      };
    }

    /** Read and update: the stored Condition; finished = its Encounter is finished. */
    @Bean
    ResourceContextResolver conditionFacts(
        Conditions conditions, Encounters encounters, ClinicalReads reads, Clock clock) {
      return (req, vars, principal) -> {
        String id = vars.get("id");
        var stored =
            id == null || !id.matches("^[A-Za-z0-9-]{1,64}$")
                ? null
                : conditions.find(id).orElse(null);
        if (stored == null) {
          throw new ShpException("not-found");
        }
        String encounterId = stored.search().getString("encounterId");
        boolean finished =
            encounterId != null
                && encounters
                    .find(encounterId)
                    .map(e -> "finished".equals(e.search().getString("status")))
                    .orElse(false);
        var b = ResourceContext.builder().patientId(stored.patientId()).encounterFinished(finished);
        return care(b, stored.patientId(), principal, reads, clock).build();
      };
    }

    private static ResourceContext.Builder care(
        ResourceContext.Builder b,
        String patientId,
        Principal principal,
        ClinicalReads reads,
        Clock clock) {
      if (!principal.roles().contains("doctor") || principal.fhirUserId() == null) {
        return b;
      }
      Instant now = Instant.now(clock);
      return b.hasCareRelationship(
              reads.careRelationship(principal.fhirUserId(), patientId, now).isPresent())
          .hasActiveBreakGlass(
              reads.breakGlass(principal.fhirUserId(), patientId, now).isPresent());
    }
  }
}
