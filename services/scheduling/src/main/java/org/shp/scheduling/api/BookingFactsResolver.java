package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Component;

/**
 * Facts for {@code Appointment/book}: which patient the booking is for. A patient who names no one
 * books for themselves (AC-03.6); a nurse must name the patient. Booking for another profile (a
 * linked profile) needs the identity link's profile list, which is not available yet, so it is
 * denied like booking for a stranger.
 */
@Component("bookingFacts")
public class BookingFactsResolver implements ResourceContextResolver {

  @Override
  public ResourceContext resolve(
      HttpServletRequest request, Map<String, String> pathVariables, Principal principal) {
    BookRequest body = BookRequest.from(request);
    UUID patient = body.patientId();
    if (patient == null) {
      if (principal.roles().contains("patient") && principal.fhirUserId() != null) {
        return ResourceContext.builder().patientId(principal.fhirUserId()).build();
      }
      if (principal.roles().contains("nurse")) {
        throw new ShpException("validation-failed");
      }
      return ResourceContext.empty(); // no rule for the role: the engine denies
    }
    return ResourceContext.builder().patientId(patient.toString()).build();
  }
}
