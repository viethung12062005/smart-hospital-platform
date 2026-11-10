package org.shp.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.util.regex.Pattern;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.shp.web.errors.ShpException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parse/serialize FHIR R4 JSON (ADR-0003) with HAPI FHIR. Strict parsing: broken JSON, duplicate
 * keys, {@code null} values, unknown elements, wrongly typed values, malformed {@code id} or a
 * {@code resourceType} mismatch → 400 {@code validation-failed}. HAPI messages may contain
 * submitted values (PII), so they are not returned to the client or written to the log.
 */
public final class FhirJson {
  /** Content type of the FHIR API (07 §1). */
  public static final String MEDIA_TYPE = "application/fhir+json";

  /** {@code Resource.id} format per the FHIR R4 spec ({@code id} type). */
  private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");

  private static final FhirContext CTX = FhirContext.forR4Cached();

  /**
   * Pre-check the syntax before HAPI: HAPI accepts duplicate keys (keeping the later value) and
   * {@code null} without complaint, so two readers of the same body could interpret it differently.
   */
  private static final JsonMapper STRICT_JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private FhirJson() {}

  /**
   * Shared R4 context (thread-safe); a parser is created fresh each time because it is not
   * thread-safe.
   */
  public static FhirContext context() {
    return CTX;
  }

  /** Parse a resource of a known type, for example the body of {@code PUT /fhir/Patient/{id}}. */
  public static <T extends IBaseResource> T parse(String json, Class<T> type) {
    requireWellFormed(json);
    try {
      return parser().parseResource(type, json);
    } catch (DataFormatException e) {
      throw new ShpException("validation-failed");
    }
  }

  /** Parse any resource, taking the type from {@code resourceType}. */
  public static IBaseResource parse(String json) {
    requireWellFormed(json);
    try {
      return parser().parseResource(json);
    } catch (DataFormatException e) {
      throw new ShpException("validation-failed");
    }
  }

  /**
   * Compact JSON (no line breaks), element order per the FHIR spec. Keeps versioned references
   * ({@code DocumentReference/x/_history/2}); by default HAPI strips the {@code _history} part.
   */
  public static String serialize(IBaseResource resource) {
    return CTX.newJsonParser()
        .setStripVersionsFromReferences(false)
        .encodeResourceToString(resource);
  }

  private static IParser parser() {
    return CTX.newJsonParser().setParserErrorHandler(new StrictErrorHandler());
  }

  private static void requireWellFormed(String json) {
    if (json == null || json.isBlank()) {
      throw new ShpException("validation-failed");
    }
    JsonNode root;
    try {
      root = STRICT_JSON.readTree(json);
    } catch (JacksonException e) {
      throw new ShpException("validation-failed");
    }
    if (!isValid(root)) {
      throw new ShpException("validation-failed");
    }
  }

  /**
   * FHIR JSON does not allow properties with a {@code null} value (only array elements may be
   * {@code null}, to align with the {@code _<name>} array that holds extensions) and the {@code id}
   * of every resource, including nested resources (contained, Bundle entries), must be a valid
   * {@code id}. HAPI checks neither of these.
   */
  private static boolean isValid(JsonNode node) {
    if (node.isObject()) {
      if (node.has("resourceType")) {
        JsonNode id = node.get("id");
        if (id != null && !(id.isString() && RESOURCE_ID.matcher(id.asString()).matches())) {
          return false;
        }
      }
      for (var entry : node.properties()) {
        if (entry.getValue().isNull() || !isValid(entry.getValue())) {
          return false;
        }
      }
    } else if (node.isArray()) {
      for (JsonNode item : node) {
        if (!isValid(item)) {
          return false;
        }
      }
    }
    return true;
  }
}
