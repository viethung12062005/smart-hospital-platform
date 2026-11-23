package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hl7.fhir.r4.model.Observation;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.clinical.app.ClinicalReads;
import org.shp.clinical.app.Encounters;
import org.shp.clinical.app.ObservationService;
import org.shp.clinical.app.Observations;
import org.shp.fhir.FhirJson;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 09 C16 Observation: create (vital signs: Nurse, or Doctor with care; laboratory: Doctor with
 * care), read, and search by patient ({@code patient} required; {@code category}, {@code code}).
 */
@RestController
public class ObservationController {
  static final Set<String> SEARCH_PARAMS = Set.of("patient", "category", "code", "_count");

  private final ObservationService service;

  public ObservationController(ObservationService service) {
    this.service = service;
  }

  @PostMapping(
      path = "/fhir/Observation",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireShpAuthz(resource = "Observation", action = "create", context = "observationCreateFacts")
  public ResponseEntity<String> create(HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    var v =
        service.create(
            RequestBodies.read(req),
            p.fhirUserId(),
            p.roles().contains("doctor"),
            EncounterController.changedBy(p));
    String id = v.resource().getIdElement().getIdPart();
    return ResponseEntity.status(HttpStatus.CREATED)
        .contentType(EncounterController.FHIR)
        .header(HttpHeaders.ETAG, ETags.format(v.version()))
        .location(URI.create("/fhir/Observation/" + id + "/_history/" + v.version()))
        .body(FhirJson.serialize(v.resource()));
  }

  @GetMapping("/fhir/Observation/{id}")
  @RequireShpAuthz(
      resource = "Observation",
      action = "read",
      context = "observationFacts",
      denyAsNotFound = true)
  public ResponseEntity<String> read(@PathVariable String id) {
    var v = service.read(id);
    return ResponseEntity.ok()
        .contentType(EncounterController.FHIR)
        .header(HttpHeaders.ETAG, ETags.format(v.version()))
        .body(FhirJson.serialize(v.resource()));
  }

  @GetMapping("/fhir/Observation")
  @RequireShpAuthz(resource = "Observation", action = "read", context = "patientQueryFacts")
  public ResponseEntity<String> search(HttpServletRequest req) {
    Map<String, List<String>> params =
        org.shp.fhir.search.SearchParser.fromServlet(req.getParameterMap());
    int count = EncounterController.count(params, SEARCH_PARAMS);
    String patientId = ClinicalFacts.searchPatient(req, IdentityInterceptor.principal(req));
    boolean patientCaller = IdentityInterceptor.principal(req).roles().contains("patient");
    List<Observation> page =
        service.byPatient(
            patientId, patientCaller, single(params, "category"), single(params, "code"), count);
    return EncounterController.bundle(req, params, page);
  }

  private static String single(Map<String, List<String>> params, String name) {
    List<String> v = params.get(name);
    if (v == null || v.isEmpty()) {
      return null;
    }
    if (v.size() > 1 || !v.get(0).matches("^[A-Za-z0-9.-]{1,64}$")) {
      throw new ShpException("validation-failed", name + " must be one code.");
    }
    return v.get(0);
  }

  /** Matrix facts of the Observation endpoints (15 §3, §4). */
  @Configuration
  static class Facts {

    /** Create: the patient is the {@code subject} of the body (read once, see RequestBodies). */
    @Bean
    ResourceContextResolver observationCreateFacts(ClinicalReads reads, Clock clock) {
      return (req, vars, principal) -> {
        Observation o;
        try {
          o = FhirJson.parse(RequestBodies.read(req), Observation.class);
        } catch (ShpException e) {
          throw e;
        } catch (RuntimeException e) {
          throw new ShpException("validation-failed", "The body is not an Observation.");
        }
        String ref = o.getSubject().getReference();
        if (ref == null || !ref.matches("^Patient/[A-Za-z0-9-]{1,64}$")) {
          throw new ShpException(
              "validation-failed", "Observation.subject must reference Patient/{id}.");
        }
        String patientId = ref.substring("Patient/".length());
        return care(
                ResourceContext.builder().patientId(patientId), patientId, principal, reads, clock)
            .build();
      };
    }

    /** Read: the stored Observation; finished = its Encounter is finished. */
    @Bean
    ResourceContextResolver observationFacts(
        Observations observations, Encounters encounters, ClinicalReads reads, Clock clock) {
      return (req, vars, principal) -> {
        String id = vars.get("id");
        var stored =
            id == null || !id.matches("^[A-Za-z0-9-]{1,64}$")
                ? null
                : observations.find(id).orElse(null);
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
