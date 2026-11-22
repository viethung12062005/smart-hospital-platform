package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.SlotBlockService;
import org.shp.scheduling.domain.SlotRecord;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.shp.web.precondition.RequireIfMatch;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** FHIR REST controller for blocking (S9) and unblocking (S10) examination slots. */
@RestController
public class SlotBlockController {

  private static final MediaType FHIR_MEDIA_TYPE = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SlotBlockService service;

  public SlotBlockController(SlotBlockService service) {
    this.service = Objects.requireNonNull(service, "service must not be null");
  }

  /**
   * S9: {@code POST /fhir/Slot/{id}/$block} — Block a free slot with a reason (1-200 characters).
   * Requires {@code If-Match}. Permissions: Nurse, Admin.
   */
  @PostMapping(
      value = "/fhir/Slot/{id}/$block",
      consumes = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireIfMatch
  @RequireShpAuthz(resource = "Slot", action = "block")
  public ResponseEntity<String> blockSlot(
      @PathVariable String id,
      @RequestBody(required = false) String rawBody,
      HttpServletRequest request) {
    UUID slotId = parseUuid(id);
    long expectedVersion = PreconditionInterceptor.expectedVersion(request);
    String reason = extractReason(rawBody);
    SlotRecord updated = service.block(slotId, expectedVersion, reason);
    return ResponseEntity.ok()
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(updated.versionId()))
        .body(updated.content());
  }

  /**
   * S10: {@code POST /fhir/Slot/{id}/$unblock} — Return a blocked ({@code busy-unavailable}) slot
   * to {@code free}. Requires {@code If-Match}. Permissions: Nurse, Admin.
   */
  @PostMapping(
      value = "/fhir/Slot/{id}/$unblock",
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireIfMatch
  @RequireShpAuthz(resource = "Slot", action = "block")
  public ResponseEntity<String> unblockSlot(@PathVariable String id, HttpServletRequest request) {
    UUID slotId = parseUuid(id);
    long expectedVersion = PreconditionInterceptor.expectedVersion(request);
    SlotRecord updated = service.unblock(slotId, expectedVersion);
    return ResponseEntity.ok()
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(updated.versionId()))
        .body(updated.content());
  }

  private static String extractReason(String rawBody) {
    if (rawBody == null || rawBody.isBlank()) {
      throw new ShpException("validation-failed");
    }
    JsonNode tree;
    try {
      tree = JSON.readTree(rawBody);
    } catch (JacksonException e) {
      throw new ShpException("validation-failed");
    }
    if (!tree.isObject()) {
      throw new ShpException("validation-failed");
    }
    JsonNode reasonNode = tree.get("reason");
    if (reasonNode == null || !reasonNode.isString()) {
      throw new ShpException("validation-failed");
    }
    String reason = reasonNode.asString().strip();
    if (reason.isEmpty() || reason.length() > SlotBlockService.MAX_REASON_LENGTH) {
      throw new ShpException("validation-failed");
    }
    return reason;
  }

  private static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ShpException("not-found");
    }
  }
}
