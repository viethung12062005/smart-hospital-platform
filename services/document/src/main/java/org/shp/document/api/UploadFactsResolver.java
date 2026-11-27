package org.shp.document.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.document.domain.CareLookup;
import org.shp.web.authz.ResourceContextResolver;
import org.springframework.stereotype.Component;

/**
 * Facts for {@code Document/upload} (15 §3): the patient named in the body and, for a doctor,
 * whether the doctor has Care with that patient. A nurse needs no Care (15 §4). An unanswered Care
 * check is an error, never an Allow.
 */
@Component("uploadFacts")
class UploadFactsResolver implements ResourceContextResolver {
  private final CareLookup care;

  UploadFactsResolver(CareLookup care) {
    this.care = care;
  }

  @Override
  public ResourceContext resolve(
      HttpServletRequest request, Map<String, String> pathVariables, Principal principal) {
    String patient = UploadRequest.patientOf(request).toString();
    var b = ResourceContext.builder().patientId(patient);
    if (principal.roles().contains("doctor") && principal.fhirUserId() != null) {
      b.practitionerId(principal.fhirUserId())
          .hasCareRelationship(care.hasCare(principal.fhirUserId(), patient));
    }
    return b.build();
  }
}
