package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Component;

/**
 * Facts for {@code Appointment/book}: which patient the booking is for. A patient who names no one
 * books for themselves (AC-03.6); a nurse must name the patient. A patient may also book for a
 * profile in their unexpired {@code linkedProfiles} (rule APPT-BOOK-LINKED); any other profile is
 * denied.
 */
@Component("bookingFacts")
public class BookingFactsResolver implements ResourceContextResolver {
  private final Clock clock;

  public BookingFactsResolver(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

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
    return ResourceContext.builder()
        .linkedFrom(principal, patient.toString(), clock.instant())
        .build();
  }
}
