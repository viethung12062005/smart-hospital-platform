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
 * Parse/serialize FHIR R4 JSON (ADR-0003) bằng HAPI FHIR. Parse nghiêm ngặt: JSON hỏng, khóa trùng,
 * giá trị {@code null}, phần tử lạ, giá trị sai kiểu, {@code id} sai định dạng hoặc {@code
 * resourceType} không khớp → 400 {@code validation-failed}. Thông điệp của HAPI có thể chứa giá trị
 * gửi lên (PII) nên không trả cho client và không ghi log.
 */
public final class FhirJson {
  /** Content type của API FHIR (07 §1). */
  public static final String MEDIA_TYPE = "application/fhir+json";

  /** Định dạng {@code Resource.id} theo đặc tả FHIR R4 (kiểu {@code id}). */
  private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");

  private static final FhirContext CTX = FhirContext.forR4Cached();

  /**
   * Kiểm cú pháp trước HAPI: HAPI nhận khóa trùng (lấy giá trị sau) và {@code null} mà không báo,
   * nên hai bản đọc cùng body có thể hiểu khác nhau.
   */
  private static final JsonMapper STRICT_JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private FhirJson() {}

  /** Context R4 dùng chung (thread-safe); parser thì tạo mới mỗi lần vì không thread-safe. */
  public static FhirContext context() {
    return CTX;
  }

  /** Parse một resource có kiểu biết trước, ví dụ body của {@code PUT /fhir/Patient/{id}}. */
  public static <T extends IBaseResource> T parse(String json, Class<T> type) {
    requireWellFormed(json);
    try {
      return parser().parseResource(type, json);
    } catch (DataFormatException e) {
      throw new ShpException("validation-failed");
    }
  }

  /** Parse một resource bất kỳ, kiểu lấy theo {@code resourceType}. */
  public static IBaseResource parse(String json) {
    requireWellFormed(json);
    try {
      return parser().parseResource(json);
    } catch (DataFormatException e) {
      throw new ShpException("validation-failed");
    }
  }

  /**
   * JSON gọn (không xuống dòng), thứ tự phần tử theo đặc tả FHIR. Giữ nguyên tham chiếu có version
   * ({@code DocumentReference/x/_history/2}); mặc định HAPI cắt mất phần {@code _history}.
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
   * FHIR JSON không cho thuộc tính mang {@code null} (chỉ phần tử trong mảng được {@code null}, để
   * căn hàng với mảng {@code _<tên>} chứa extension) và {@code id} của mọi resource, kể cả resource
   * lồng (contained, entry của Bundle), phải đúng kiểu {@code id}. HAPI không kiểm hai điều này.
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
