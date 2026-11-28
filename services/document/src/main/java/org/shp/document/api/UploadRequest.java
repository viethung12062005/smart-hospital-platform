package org.shp.document.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.shp.events.CloudEvents;
import org.shp.web.errors.ShpException;
import org.shp.web.idempotency.IdempotencyKeys;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Body of D1 {@code POST /api/v1/documents/uploads} (10 §2). Authorization needs the patient before
 * the handler runs, so the body is parsed once and kept on the request: {@link #patientOf} is the
 * cheap first step (shape of the body, patient id), {@link #from} the full validation.
 */
record UploadRequest(
    UUID patientId,
    UUID encounterId,
    String type,
    String title,
    String description,
    String fileName,
    String contentType,
    long size,
    String sha256,
    byte[] hash) {
  static final long MAX_SIZE = 20_971_520L;
  static final int MAX_BODY_BYTES = 16 * 1024;
  static final Set<String> TYPES =
      Set.of("11502-2", "18748-4", "57833-6", "57133-1", "34133-9", "51851-4");
  static final Map<String, String> EXTENSIONS =
      Map.of("application/pdf", "pdf", "image/jpeg", "jpg", "image/png", "png");

  private static final String ATTR_BODY = "shp.document.uploadBody";
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  private record Raw(JsonNode json, byte[] bytes) {}

  /** The patient the upload is for; 400 {@code validation-failed} when absent or malformed. */
  static UUID patientOf(HttpServletRequest req) {
    return uuid(raw(req).json(), "patientId", true);
  }

  /** Full validation: 400 for shape, 415 for the file type, 413 for the size (10 §2). */
  static UploadRequest from(HttpServletRequest req) {
    Raw raw = raw(req);
    JsonNode j = raw.json();
    UUID patient = uuid(j, "patientId", true);
    UUID encounter = uuid(j, "encounterId", false);
    String type = text(j, "type", true, 32);
    String title = text(j, "title", false, 200);
    String description = text(j, "description", false, 1000);
    String fileName = text(j, "fileName", true, 255);
    String contentType = text(j, "contentType", true, 100);
    String sha256 = text(j, "sha256", true, 64);
    JsonNode sizeNode = j.get("size");
    if (sizeNode == null || !sizeNode.isIntegralNumber() || !sizeNode.canConvertToLong()) {
      throw invalid();
    }
    if (!TYPES.contains(type)
        || fileName.contains("/")
        || fileName.contains("\\")
        || fileName.contains("..")
        || fileName.chars().anyMatch(c -> c < ' ' || c == 127)
        || !SHA256.matcher(sha256).matches()) {
      throw invalid();
    }
    if (!EXTENSIONS.containsKey(contentType)) {
      throw new ShpException("invalid-file-type");
    }
    long size = sizeNode.asLong();
    if (size <= 0 || size > MAX_SIZE) {
      throw new ShpException("file-too-large");
    }
    return new UploadRequest(
        patient,
        encounter,
        type,
        title,
        description,
        fileName,
        contentType,
        size,
        sha256,
        IdempotencyKeys.hashBody(raw.bytes()));
  }

  private static Raw raw(HttpServletRequest req) {
    if (req.getAttribute(ATTR_BODY) instanceof Raw cached) {
      return cached;
    }
    try (InputStream in = req.getInputStream()) {
      byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) {
        throw invalid();
      }
      JsonNode json = CloudEvents.JSON.readTree(bytes);
      if (json == null || !json.isObject()) {
        throw invalid();
      }
      Raw raw = new Raw(json, bytes);
      req.setAttribute(ATTR_BODY, raw);
      return raw;
    } catch (IOException | JacksonException e) {
      throw invalid();
    }
  }

  private static String text(JsonNode j, String name, boolean required, int max) {
    JsonNode n = j.get(name);
    if (n == null || n.isNull()) {
      if (required) {
        throw invalid();
      }
      return null;
    }
    if (!n.isString() || n.asString().isBlank() || n.asString().length() > max) {
      throw invalid();
    }
    return n.asString();
  }

  private static UUID uuid(JsonNode j, String name, boolean required) {
    String v = text(j, name, required, 36);
    if (v == null) {
      return null;
    }
    if (!UUID_PATTERN.matcher(v).matches()) {
      throw invalid();
    }
    return UUID.fromString(v);
  }

  private static ShpException invalid() {
    return new ShpException("validation-failed");
  }
}
