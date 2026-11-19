package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.shp.authz.Principal;
import org.shp.clinical.app.EncounterClosing;
import org.shp.fhir.FhirJson;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ErrorWriter;
import org.shp.web.precondition.ETags;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 09 C14 {@code POST /fhir/Encounter/{id}/$finish}: a doctor with care ends an unfinished visit
 * (matrix {@code Encounter: update}, conditions care + unfinished-encounter).
 */
@RestController
public class EncounterFinishController {
  private final EncounterClosing closing;

  public EncounterFinishController(EncounterClosing closing) {
    this.closing = closing;
  }

  @PostMapping("/fhir/Encounter/{id}/$finish")
  @RequireShpAuthz(resource = "Encounter", action = "update", context = "encounterFacts")
  public ResponseEntity<String> finish(@PathVariable String id, HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    String doctor = p.roles().contains("doctor") ? p.fhirUserId() : null;
    var v = closing.finish(id, doctor, EncounterController.changedBy(p));
    return ResponseEntity.ok()
        .contentType(EncounterController.FHIR)
        .header(HttpHeaders.ETAG, ETags.format(v.version()))
        .body(FhirJson.serialize(v.resource()));
  }

  /** 422 {@code diagnosis-required}. */
  @ExceptionHandler(EncounterClosing.DiagnosisRequiredException.class)
  void diagnosisRequired(
      EncounterClosing.DiagnosisRequiredException ex,
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    ErrorWriter.write(req, res, EncounterClosing.DIAGNOSIS_REQUIRED, ex.getMessage(), null);
  }
}
