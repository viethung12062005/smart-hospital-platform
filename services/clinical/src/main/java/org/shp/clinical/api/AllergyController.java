package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.clinical.app.Allergies;
import org.shp.clinical.app.AllergyService;
import org.shp.clinical.app.ClinicalReads;
import org.shp.fhir.FhirJson;
import org.shp.fhir.search.SearchParser;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.RequireIfMatch;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 09 C18 AllergyIntolerance: create and update by a doctor with care or a nurse; list by patient
 * (the patient their own; doctors with care or break-glass; nurses).
 */
@RestController
public class AllergyController {
  static final Set<String> SEARCH_PARAMS = Set.of("patient", "_count");

  private final AllergyService service;

  public AllergyController(AllergyService service) {
    this.service = service;
  }

  @PostMapping(
      path = "/fhir/AllergyIntolerance",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireShpAuthz(
      resource = "AllergyIntolerance",
      action = "create",
      context = "allergyCreateFacts")
  public ResponseEntity<String> create(HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    var v =
        service.create(RequestBodies.read(req), p.fhirUserId(), EncounterController.changedBy(p));
    String id = v.resource().getIdElement().getIdPart();
    return ResponseEntity.status(HttpStatus.CREATED)
        .contentType(EncounterController.FHIR)
        .header(HttpHeaders.ETAG, ETags.format(v.version()))
        .location(URI.create("/fhir/AllergyIntolerance/" + id + "/_history/" + v.version()))
        .body(FhirJson.serialize(v.resource()));
  }

  @PutMapping(
      path = "/fhir/AllergyIntolerance/{id}",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireIfMatch
  @RequireShpAuthz(resource = "AllergyIntolerance", action = "update", context = "allergyFacts")
  public ResponseEntity<String> update(
      @PathVariable String id, @RequestBody String body, HttpServletRequest req) {
    var v =
        service.update(
            id,
            body,
            EncounterController.expectedVersion(req),
            EncounterController.changedBy(IdentityInterceptor.principal(req)));
    return ResponseEntity.ok()
        .contentType(EncounterController.FHIR)
        .header(HttpHeaders.ETAG, ETags.format(v.version()))
        .body(FhirJson.serialize(v.resource()));
  }

  @GetMapping("/fhir/AllergyIntolerance")
  @RequireShpAuthz(resource = "AllergyIntolerance", action = "read", context = "patientQueryFacts")
  public ResponseEntity<String> search(HttpServletRequest req) {
    Map<String, List<String>> params = SearchParser.fromServlet(req.getParameterMap());
    int count = EncounterController.count(params, SEARCH_PARAMS);
    String patientId = ClinicalFacts.searchPatient(req, IdentityInterceptor.principal(req));
    List<AllergyIntolerance> page = service.byPatient(patientId, count);
    return EncounterController.bundle(req, params, page);
  }

  /** Matrix facts of the AllergyIntolerance endpoints (15 §3, §4). */
  @Configuration
  static class Facts {

    /** Create: the patient of the body (read once, see RequestBodies). */
    @Bean
    ResourceContextResolver allergyCreateFacts(ClinicalReads reads, Clock clock) {
      return (req, vars, principal) -> {
        AllergyIntolerance a;
        try {
          a = FhirJson.parse(RequestBodies.read(req), AllergyIntolerance.class);
        } catch (ShpException e) {
          throw e;
        } catch (RuntimeException e) {
          throw new ShpException("validation-failed", "The body is not an AllergyIntolerance.");
        }
        String ref = a.getPatient().getReference();
        if (ref == null || !ref.matches("^Patient/[A-Za-z0-9-]{1,64}$")) {
          throw new ShpException(
              "validation-failed", "AllergyIntolerance.patient must reference Patient/{id}.");
        }
        String patientId = ref.substring("Patient/".length());
        return care(
                ResourceContext.builder().patientId(patientId), patientId, principal, reads, clock)
            .build();
      };
    }

    /** Update: the stored allergy's patient. */
    @Bean
    ResourceContextResolver allergyFacts(Allergies allergies, ClinicalReads reads, Clock clock) {
      return (req, vars, principal) -> {
        String id = vars.get("id");
        var stored =
            id == null || !id.matches("^[A-Za-z0-9-]{1,64}$")
                ? null
                : allergies.find(id).orElse(null);
        if (stored == null) {
          throw new ShpException("not-found");
        }
        return care(
                ResourceContext.builder().patientId(stored.patientId()),
                stored.patientId(),
                principal,
                reads,
                clock)
            .build();
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
