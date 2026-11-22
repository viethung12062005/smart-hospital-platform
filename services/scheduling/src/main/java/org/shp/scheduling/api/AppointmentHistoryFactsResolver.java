package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.scheduling.infra.AppointmentHistoryRepository;
import org.shp.web.authz.ResourceContextResolver;
import org.springframework.stereotype.Component;

/**
 * Resolves authorization context for {@code GET /fhir/Appointment/{id}/_history}: attaches {@code
 * patientId} for audit attribution when the appointment exists, and returns empty context otherwise
 * so denied roles still receive {@code 403 forbidden} without leaking resource existence.
 */
@Component("appointmentHistoryFacts")
public class AppointmentHistoryFactsResolver implements ResourceContextResolver {

  private final AppointmentHistoryRepository repository;

  public AppointmentHistoryFactsResolver(AppointmentHistoryRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
  }

  @Override
  public ResourceContext resolve(
      HttpServletRequest request, Map<String, String> pathVariables, Principal principal) {
    UUID appointmentId = parseUuidOrNull(pathVariables.get("id"));
    if (appointmentId == null) {
      return ResourceContext.empty();
    }
    return repository
        .findPatientId(appointmentId)
        .map(patientId -> ResourceContext.builder().patientId(patientId.toString()).build())
        .orElseGet(ResourceContext::empty);
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
