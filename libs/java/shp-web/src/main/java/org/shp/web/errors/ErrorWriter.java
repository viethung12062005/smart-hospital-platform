package org.shp.web.errors;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.shp.events.CloudEvents;

/**
 * Ghi lỗi đúng định dạng: {@code /fhir/*} → OperationOutcome; còn lại → Problem Details (RFC 9457)
 * có trường {@code code}.
 */
public final class ErrorWriter {
  public static final String FHIR_ERROR_SYSTEM = "https://shp.example.org/fhir/CodeSystem/error";

  private ErrorWriter() {}

  public static void write(
      HttpServletRequest req,
      HttpServletResponse res,
      ErrorInfo info,
      String detail,
      Duration retryAfter)
      throws IOException {
    String traceId = traceId();
    String message =
        detail != null ? detail : wantsEnglish(req) ? info.messageEn() : info.messageVi();
    res.setStatus(info.status());
    res.setHeader("X-Request-Id", traceId);
    if (retryAfter != null || info.status() == 429 || info.status() == 503) {
      res.setHeader(
          "Retry-After",
          Long.toString((retryAfter != null ? retryAfter : Duration.ofSeconds(5)).toSeconds()));
    }
    res.setCharacterEncoding("UTF-8");
    Object body;
    if (req.getRequestURI().startsWith("/fhir/")) {
      res.setContentType("application/fhir+json");
      body =
          Map.of(
              "resourceType",
              "OperationOutcome",
              "issue",
              List.of(
                  Map.of(
                      "severity",
                      "error",
                      "code",
                      info.fhirIssueType(),
                      "details",
                      Map.of(
                          "coding",
                          List.of(Map.of("system", FHIR_ERROR_SYSTEM, "code", info.code()))),
                      "diagnostics",
                      message)));
    } else {
      res.setContentType("application/problem+json");
      Map<String, Object> problem = new LinkedHashMap<>();
      problem.put("type", "https://shp.example.org/errors/" + info.code());
      problem.put("title", info.code());
      problem.put("status", info.status());
      problem.put("code", info.code());
      problem.put("detail", message);
      problem.put("traceId", traceId);
      body = problem;
    }
    res.getWriter().write(CloudEvents.JSON.writeValueAsString(body));
  }

  static String traceId() {
    var ctx = Span.current().getSpanContext();
    return ctx.isValid() ? ctx.getTraceId() : UUID.randomUUID().toString().replace("-", "");
  }

  private static boolean wantsEnglish(HttpServletRequest req) {
    String lang = req.getHeader("Accept-Language");
    return lang != null && lang.toLowerCase(java.util.Locale.ROOT).startsWith("en");
  }
}
