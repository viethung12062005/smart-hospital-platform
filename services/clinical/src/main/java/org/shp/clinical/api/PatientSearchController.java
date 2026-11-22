package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Patient;
import org.shp.authz.Principal;
import org.shp.clinical.app.PatientSearchService;
import org.shp.fhir.FhirJson;
import org.shp.fhir.search.SearchBundles;
import org.shp.fhir.search.SearchParser;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 09 C6 {@code POST /fhir/Patient/_search} (form-urlencoded; doctors and nurses, matrix {@code
 * PAT-SEARCH-*}). The searchset Bundle carries no search values in its links: names, phones and
 * birth dates are personal data. The shared interceptor writes a {@code search} audit record
 * without the values.
 */
@RestController
public class PatientSearchController {
  static final MediaType FHIR = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);

  private final PatientSearchService service;

  public PatientSearchController(PatientSearchService service) {
    this.service = service;
  }

  @PostMapping(path = "/fhir/Patient/_search", consumes = "application/x-www-form-urlencoded")
  @RequireShpAuthz(resource = "Patient", action = "search")
  public ResponseEntity<String> search(HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    Map<String, List<String>> params = SearchParser.fromServlet(req.getParameterMap());
    String doctor = p.roles().contains("doctor") ? p.fhirUserId() : null;
    List<Patient> page = service.search(params, doctor);
    String base = ServletUriComponentsBuilder.fromRequestUri(req).replaceQuery(null).toUriString();
    return ResponseEntity.ok()
        .contentType(FHIR)
        .body(FhirJson.serialize(SearchBundles.searchset(base, Map.of(), page, null)));
  }
}
