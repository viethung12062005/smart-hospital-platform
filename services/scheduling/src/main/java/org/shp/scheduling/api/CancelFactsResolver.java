package org.shp.scheduling.api;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.shp.authz.ResourceContext;
import org.shp.scheduling.domain.AppointmentSearchCriteria.AppointmentRow;
import org.shp.scheduling.infra.AppointmentSearchRepository;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Facts for {@code Appointment/cancel} (S14). A patient who owns the appointment (or manages its
 * profile) and cancels with less than 2 hours left gets 422 {@code cancellation-too-late} (BR-02,
 * AC-03.5), not a plain denial; another patient's appointment stays a 403; a nurse may always
 * cancel. An unknown appointment is 404 for patient and nurse.
 */
@Configuration
public class CancelFactsResolver {
  static final Duration CANCEL_WINDOW = Duration.ofHours(2);

  @Bean("appointmentCancelFacts")
  ResourceContextResolver appointmentCancelFacts(
      AppointmentSearchRepository repository, Clock clock) {
    return (request, pathVariables, principal) -> {
      boolean patient = principal.roles().contains("patient");
      boolean nurse = principal.roles().contains("nurse");
      if (!patient && !nurse) {
        return ResourceContext.empty(); // no rule for the role: the engine denies
      }
      UUID id = parse(pathVariables.get("id"));
      AppointmentRow row = repository.findById(id).orElseThrow(() -> new ShpException("not-found"));
      String patientId = row.patientId().toString();
      if (nurse) {
        return ResourceContext.builder().patientId(patientId).build();
      }
      boolean mine =
          patientId.equals(principal.fhirUserId())
              || principal.linkTo(patientId, clock.instant()).isPresent();
      if (mine && Duration.between(clock.instant(), row.startAt()).compareTo(CANCEL_WINDOW) < 0) {
        throw new ShpException("cancellation-too-late");
      }
      return ResourceContext.builder()
          .linkedFrom(principal, patientId, clock.instant())
          .appointmentStart(row.startAt())
          .build();
    };
  }

  private static UUID parse(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw new ShpException("not-found");
    }
  }
}
