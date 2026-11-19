package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import org.shp.authz.Principal;
import org.shp.clinical.app.EncounterAddenda;
import org.shp.events.CloudEvents;
import org.shp.fhir.FhirJson;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 09 C15 {@code POST /fhir/Encounter/{id}/$addendum} with {@code { "text": "..." }}. Authorized as
 * {@code Encounter: create} with care (an addendum is a new entry; the update rules require an
 * unfinished visit, an addendum a finished one); the service allows doctors only.
 */
@RestController
public class EncounterAddendumController {
  private final EncounterAddenda addenda;

  public EncounterAddendumController(EncounterAddenda addenda) {
    this.addenda = addenda;
  }

  @PostMapping(path = "/fhir/Encounter/{id}/$addendum", consumes = "application/json")
  @RequireShpAuthz(resource = "Encounter", action = "create", context = "encounterFacts")
  public ResponseEntity<String> add(
      @PathVariable String id, @RequestBody String body, HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    String doctor = p.roles().contains("doctor") ? p.fhirUserId() : null;
    var v = addenda.add(id, text(body), doctor, EncounterController.changedBy(p));
    return ResponseEntity.ok()
        .contentType(EncounterController.FHIR)
        .header(HttpHeaders.ETAG, ETags.format(v.version()))
        .body(FhirJson.serialize(v.resource()));
  }

  /** The body is exactly {@code { "text": "<string>" }}. */
  public static String text(String body) {
    JsonNode node;
    try {
      node = CloudEvents.JSON.readTree(body);
    } catch (RuntimeException e) {
      throw new ShpException("validation-failed", "The body must be { \"text\": \"...\" }.");
    }
    if (node == null
        || !node.isObject()
        || node.size() != 1
        || !node.has("text")
        || !node.get("text").isString()) {
      throw new ShpException("validation-failed", "The body must be { \"text\": \"...\" }.");
    }
    return node.get("text").asString();
  }
}
