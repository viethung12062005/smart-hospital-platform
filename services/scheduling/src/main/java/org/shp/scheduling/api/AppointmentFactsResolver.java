package org.shp.scheduling.api;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.shp.authz.AuthzDecision;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.events.audit.AuditSink;
import org.shp.events.audit.AuthzAudit;
import org.shp.scheduling.domain.AppointmentSearchCriteria.AppointmentRow;
import org.shp.scheduling.infra.AppointmentSearchRepository;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Authorization fact resolvers for S12 ({@code appointmentReadFacts}) and S13 ({@code
 * appointmentSearchFacts}).
 *
 * <p>On S12 {@code GET /fhir/Appointment/{id}}: Patient (not own) and Doctor (not own) receive
 * {@code 404 not-found} so appointment existence is not disclosed (US-013, US-018, 07 §4, 15 §6),
 * while the denial is explicitly audited; Admin and DPO receive {@code 403 forbidden}.
 */
@Configuration
public class AppointmentFactsResolver {

  /** Request attribute used to pass the loaded appointment row from facts resolution to S12. */
  public static final String ATTR_APPOINTMENT = "shp.scheduling.appointment";

  @Bean("appointmentReadFacts")
  ResourceContextResolver appointmentReadFacts(
      AppointmentSearchRepository repository, AuditSink audit, Clock clock) {
    return (request, pathVariables, principal) -> {
      String rawId = pathVariables.get("id");
      UUID uuid = parseUuidOrNull(rawId);

      boolean patient = principal.roles().contains("patient");
      boolean doctor = principal.roles().contains("doctor");
      boolean nurse = principal.roles().contains("nurse");

      if (!patient && !doctor && !nurse) {
        if (uuid == null) {
          return ResourceContext.empty();
        }
        return repository
            .findById(uuid)
            .map(row ->
                ResourceContext.builder()
                    .patientId(row.patientId().toString())
                    .practitionerId(row.practitionerId().toString())
                    .build())
            .orElseGet(ResourceContext::empty);
      }

      if (uuid == null) {
        throw new ShpException("not-found");
      }
      AppointmentRow row =
          repository.findById(uuid).orElseThrow(() -> new ShpException("not-found"));
      request.setAttribute(ATTR_APPOINTMENT, row);

      ResourceContext ctx =
          ResourceContext.builder()
              .patientId(row.patientId().toString())
              .practitionerId(row.practitionerId().toString())
              .build();
      if (nurse) {
        return ctx;
      }

      boolean doctorOwn = doctor && row.practitionerId().toString().equals(principal.fhirUserId());
      boolean patientOwn = patient && row.patientId().toString().equals(principal.fhirUserId());
      if (doctorOwn || patientOwn) {
        return ctx;
      }

      String denyReason = doctor ? "unmet-self-practitioner" : "unmet-own";
      auditDenied(request, principal, uuid, row, denyReason, audit, clock);
      throw new ShpException("not-found");
    };
  }

  @Bean("appointmentSearchFacts")
  ResourceContextResolver appointmentSearchFacts() {
    return (request, pathVariables, principal) -> {
      if (principal.roles().contains("nurse")) {
        return ResourceContext.empty();
      }
      if (principal.roles().contains("doctor") && principal.fhirUserId() != null) {
        return ResourceContext.builder().practitionerId(principal.fhirUserId()).build();
      }
      if (principal.roles().contains("patient") && principal.fhirUserId() != null) {
        return ResourceContext.builder().patientId(principal.fhirUserId()).build();
      }
      return ResourceContext.empty();
    };
  }

  private static void auditDenied(
      HttpServletRequest request,
      Principal principal,
      UUID appointmentId,
      AppointmentRow row,
      String denyReason,
      AuditSink audit,
      Clock clock) {
    var span = Span.current().getSpanContext();
    String traceId = span.isValid() ? span.getTraceId() : null;
    String fhirUserType = principal.roles().contains("patient") ? "Patient" : "Practitioner";
    audit.emit(
        AuthzAudit.fromDecision(
            clock,
            "scheduling",
            principal,
            fhirUserType,
            "read",
            "Appointment/" + appointmentId,
            AuthzDecision.deny(denyReason),
            row.patientId(),
            traceId,
            request.getRemoteAddr(),
            request.getHeader("User-Agent"),
            null));
  }

  private static UUID parseUuidOrNull(String rawId) {
    if (rawId == null || rawId.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(rawId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
