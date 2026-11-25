package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;
import org.shp.web.idempotency.IdempotencyKeys;

/**
 * The parsed body of {@code POST /fhir/Appointment/{id}/$cancel} (08 §4, S14): one {@code reason}
 * code from the cancel-reason code system.
 *
 * @param reason the cancellation reason code
 * @param hash SHA-256 of the canonicalized body, the Idempotency-Key request hash
 */
record CancelRequest(String reason, byte[] hash) {
  static final int MAX_BODY_BYTES = 4 * 1024;
  static final Set<String> REASONS =
      Set.of(
          "patient-request",
          "practitioner-unavailable",
          "duplicate",
          "rescheduled",
          "system-noshow-cleanup",
          "other");

  static CancelRequest from(HttpServletRequest req) {
    byte[] body = readBody(req);
    Parameters params;
    try {
      params = FhirJson.parse(new String(body, StandardCharsets.UTF_8), Parameters.class);
    } catch (RuntimeException e) {
      throw new ShpException("validation-failed");
    }
    String reason = null;
    for (ParametersParameterComponent p : params.getParameter()) {
      if (!"reason".equals(p.getName())
          || reason != null
          || p.hasPart()
          || p.hasResource()
          || p.getValue() == null
          || !"code".equals(p.getValue().fhirType())) {
        throw new ShpException("validation-failed");
      }
      reason = p.getValue().primitiveValue();
    }
    if (reason == null || !REASONS.contains(reason)) {
      throw new ShpException("validation-failed");
    }
    return new CancelRequest(reason, IdempotencyKeys.hashBody(body));
  }

  private static byte[] readBody(HttpServletRequest req) {
    try (InputStream in = req.getInputStream()) {
      byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
      if (body.length == 0 || body.length > MAX_BODY_BYTES) {
        throw new ShpException("validation-failed");
      }
      return body;
    } catch (IOException e) {
      throw new ShpException("validation-failed");
    }
  }
}
