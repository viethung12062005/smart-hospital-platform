package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.clinical.app.ClinicalReads;
import org.shp.clinical.app.Encounters;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Facts the authorization matrix needs for clinical resources (15 §3, §4): the patient, the
 * attending practitioner, whether the CALLER has care or an active break-glass session with that
 * patient, and whether the Encounter is finished.
 *
 * <ul>
 *   <li>{@code encounterFacts}: the Encounter of the {@code {id}} path variable (404 when absent).
 *   <li>{@code encounterEditFacts}: the same, but reports the Encounter as not finished, so the
 *       matrix checks care and the service answers a finished visit with 409 {@code
 *       invalid-state-transition} (US-024 AC-3) instead of 403.
 *   <li>{@code patientQueryFacts}: the patient of the {@code patient} query parameter (search).
 * </ul>
 */
@Configuration
class ClinicalFacts {
  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  @Bean
  ResourceContextResolver encounterFacts(Encounters encounters, ClinicalReads reads, Clock clock) {
    return (req, vars, principal) -> encounter(vars, principal, encounters, reads, clock, true);
  }

  @Bean
  ResourceContextResolver encounterEditFacts(
      Encounters encounters, ClinicalReads reads, Clock clock) {
    return (req, vars, principal) -> encounter(vars, principal, encounters, reads, clock, false);
  }

  @Bean
  ResourceContextResolver patientQueryFacts(ClinicalReads reads, Clock clock) {
    return (req, vars, principal) -> {
      String patientId = patientParam(req);
      // A Patient caller's search is restricted to finished Encounters by the service, so every
      // result satisfies the encounter-finished condition of the patient read rules.
      var b =
          ResourceContext.builder()
              .patientId(patientId)
              .encounterFinished(principal.roles().contains("patient"));
      return care(b, patientId, principal, reads, clock).build();
    };
  }

  /** The required {@code patient} parameter of a search, as a bare id (400 otherwise). */
  static String patientParam(HttpServletRequest req) {
    String[] values = req.getParameterValues("patient");
    if (values == null || values.length != 1) {
      throw new ShpException("validation-failed", "patient is required, once.");
    }
    String v = values[0].startsWith("Patient/") ? values[0].substring(8) : values[0];
    if (!ID.matcher(v).matches()) {
      throw new ShpException("validation-failed", "patient must be a Patient id.");
    }
    return v;
  }

  private static ResourceContext encounter(
      Map<String, String> vars,
      Principal principal,
      Encounters encounters,
      ClinicalReads reads,
      Clock clock,
      boolean realState) {
    String id = vars.get("id");
    var stored =
        (id == null || !ID.matcher(id).matches() ? null : encounters.find(id).orElse(null));
    if (stored == null) {
      throw new ShpException("not-found");
    }
    String status = stored.search().getString("status");
    var b =
        ResourceContext.builder()
            .patientId(stored.patientId())
            .practitionerId(stored.search().getString("practitionerId"))
            .encounterFinished(realState && "finished".equals(status));
    return care(b, stored.patientId(), principal, reads, clock).build();
  }

  /** Care and break-glass of the calling practitioner with the patient (15 §4, §5). */
  private static ResourceContext.Builder care(
      ResourceContext.Builder b,
      String patientId,
      Principal principal,
      ClinicalReads reads,
      Clock clock) {
    String practitionerId = principal.roles().contains("doctor") ? principal.fhirUserId() : null;
    if (practitionerId == null) {
      return b;
    }
    Instant now = Instant.now(clock);
    return b.hasCareRelationship(reads.careRelationship(practitionerId, patientId, now).isPresent())
        .hasActiveBreakGlass(reads.breakGlass(practitionerId, patientId, now).isPresent());
  }
}
