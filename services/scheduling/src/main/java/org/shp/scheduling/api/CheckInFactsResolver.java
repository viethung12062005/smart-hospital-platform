package org.shp.scheduling.api;

import java.util.UUID;
import org.shp.authz.ResourceContext;
import org.shp.scheduling.infra.AppointmentSearchRepository;
import org.shp.web.authz.ResourceContextResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Authorization facts for {@code Appointment/check-in} (S16): attaches {@code patientId} and {@code
 * practitionerId} to the audit record when the appointment exists, without throwing {@code 404}
 * before authorization runs so non-Nurse callers receive {@code 403 forbidden} with a {@code
 * denied} audit entry.
 */
@Configuration
public class CheckInFactsResolver {

  @Bean("appointmentCheckInFacts")
  ResourceContextResolver appointmentCheckInFacts(AppointmentSearchRepository repository) {
    return (request, pathVariables, principal) -> {
      UUID id = parseUuidOrNull(pathVariables.get("id"));
      if (id == null) {
        return ResourceContext.empty();
      }
      return repository
          .findById(id)
          .map(row ->
              ResourceContext.builder()
                  .patientId(row.patientId().toString())
                  .practitionerId(row.practitionerId().toString())
                  .build())
          .orElseGet(ResourceContext::empty);
    };
  }

  private static UUID parseUuidOrNull(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
