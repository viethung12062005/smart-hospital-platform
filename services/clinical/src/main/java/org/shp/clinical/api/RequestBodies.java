package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.shp.web.errors.ShpException;

/**
 * The body of a create request, read once. The authorization facts of a create (the patient of the
 * new Observation or Condition) are in the body, and the facts resolver runs before the controller:
 * it reads the body here and the controller takes the same text, so the stream is consumed only
 * once.
 */
final class RequestBodies {
  static final String ATTRIBUTE = RequestBodies.class.getName() + ".body";

  /** 07 §1: request bodies are small FHIR resources. */
  static final int MAX_BYTES = 256 * 1024;

  private RequestBodies() {}

  static String read(HttpServletRequest req) {
    if (req.getAttribute(ATTRIBUTE) instanceof String cached) {
      return cached;
    }
    try {
      byte[] bytes = req.getInputStream().readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) {
        throw new ShpException("validation-failed", "The request body is too large.");
      }
      String body = new String(bytes, StandardCharsets.UTF_8);
      req.setAttribute(ATTRIBUTE, body);
      return body;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
