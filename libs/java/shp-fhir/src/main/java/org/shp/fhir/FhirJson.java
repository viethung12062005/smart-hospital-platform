package org.shp.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.shp.web.errors.ShpException;

/**
 * Parse/serialize FHIR R4 JSON (ADR-0003) bằng HAPI FHIR. Parse nghiêm ngặt: JSON hỏng, phần tử lạ,
 * giá trị sai kiểu hoặc {@code resourceType} không khớp → 400 {@code validation-failed}. Thông điệp
 * của HAPI có thể chứa giá trị gửi lên (PII) nên không trả cho client và không ghi log.
 */
public final class FhirJson {
  /** Content type của API FHIR (07 §1). */
  public static final String MEDIA_TYPE = "application/fhir+json";

  private static final FhirContext CTX = FhirContext.forR4Cached();

  private FhirJson() {}

  /** Context R4 dùng chung (thread-safe); parser thì tạo mới mỗi lần vì không thread-safe. */
  public static FhirContext context() {
    return CTX;
  }

  /** Parse một resource có kiểu biết trước, ví dụ body của {@code PUT /fhir/Patient/{id}}. */
  public static <T extends IBaseResource> T parse(String json, Class<T> type) {
    requireBody(json);
    try {
      return parser().parseResource(type, json);
    } catch (DataFormatException e) {
      throw new ShpException("validation-failed");
    }
  }

  /** Parse một resource bất kỳ, kiểu lấy theo {@code resourceType}. */
  public static IBaseResource parse(String json) {
    requireBody(json);
    try {
      return parser().parseResource(json);
    } catch (DataFormatException e) {
      throw new ShpException("validation-failed");
    }
  }

  /** JSON gọn (không xuống dòng), thứ tự phần tử theo đặc tả FHIR. */
  public static String serialize(IBaseResource resource) {
    return CTX.newJsonParser().encodeResourceToString(resource);
  }

  private static IParser parser() {
    return CTX.newJsonParser().setParserErrorHandler(new StrictErrorHandler());
  }

  private static void requireBody(String json) {
    if (json == null || json.isBlank()) {
      throw new ShpException("validation-failed");
    }
  }
}
