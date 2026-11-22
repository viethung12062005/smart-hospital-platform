package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Reference;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;
import org.shp.web.idempotency.IdempotencyKeys;

/**
 * The parsed body of {@code POST /fhir/Appointment/$book} (08 §4, S11). The authorization step
 * needs the patient before the handler runs, so the body is read once and kept on the request.
 *
 * @param patientId the {@code patient} parameter, null when the caller did not send one
 * @param channel the {@code channel} parameter, null when the caller did not send one
 * @param comment optional note of at most 500 characters
 * @param hash SHA-256 of the canonicalized body, the Idempotency-Key request hash
 */
record BookRequest(UUID slotId, UUID patientId, String comment, String channel, byte[] hash) {
  static final int MAX_BODY_BYTES = 16 * 1024;
  static final int MAX_COMMENT = 500;
  static final Set<String> CHANNELS = Set.of("web", "chatbot", "staff");

  private static final String ATTR = "shp.scheduling.bookRequest";
  private static final Set<String> NAMES = Set.of("slot", "patient", "comment", "channel");
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  /** Read and validate the body once per request; later calls return the same object. */
  static BookRequest from(HttpServletRequest req) {
    if (req.getAttribute(ATTR) instanceof BookRequest parsed) {
      return parsed;
    }
    BookRequest parsed = parse(readBody(req));
    req.setAttribute(ATTR, parsed);
    return parsed;
  }

  static BookRequest parse(byte[] body) {
    String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
    Parameters params = FhirJson.parse(json, Parameters.class);
    UUID slot = null;
    UUID patient = null;
    String comment = null;
    String channel = null;
    Set<String> seen = new HashSet<>();
    for (ParametersParameterComponent p : params.getParameter()) {
      String name = p.getName();
      if (name == null
          || !NAMES.contains(name)
          || !seen.add(name)
          || p.hasPart()
          || p.hasResource()) {
        throw new ShpException("validation-failed");
      }
      switch (name) {
        case "slot" -> slot = reference(p, "Slot");
        case "patient" -> patient = reference(p, "Patient");
        case "comment" -> {
          comment =
              p.getValue() != null && "string".equals(p.getValue().fhirType())
                  ? p.getValue().primitiveValue()
                  : null;
          if (comment == null || comment.length() > MAX_COMMENT) {
            throw new ShpException("validation-failed");
          }
        }
        default -> {
          channel =
              p.getValue() != null && "code".equals(p.getValue().fhirType())
                  ? p.getValue().primitiveValue()
                  : null;
          if (channel == null || !CHANNELS.contains(channel)) {
            throw new ShpException("validation-failed");
          }
        }
      }
    }
    if (slot == null) {
      throw new ShpException("validation-failed");
    }
    return new BookRequest(slot, patient, comment, channel, IdempotencyKeys.hashBody(body));
  }

  private static UUID reference(ParametersParameterComponent p, String type) {
    if (!(p.getValue() instanceof Reference r) || r.getReference() == null) {
      throw new ShpException("validation-failed");
    }
    String ref = r.getReference();
    Matcher m = Pattern.compile("^" + type + "/(.+)$").matcher(ref);
    if (!m.matches() || !UUID_PATTERN.matcher(m.group(1)).matches()) {
      throw new ShpException("validation-failed");
    }
    return UUID.fromString(m.group(1));
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
