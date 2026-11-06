package org.shp.web.precondition;

import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;

/**
 * Đặt {@code ETag: W/"<versionId>"} trên mọi response trả một resource (07 §3): resource FHIR theo
 * {@code meta.versionId} (Map hoặc JsonNode), DTO theo {@link Versioned}. Không ghi đè ETag handler
 * đã đặt; danh sách, Bundle và body không có version thì bỏ qua.
 */
@RestControllerAdvice
public class ETagAdvice implements ResponseBodyAdvice<Object> {

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    Long version = versionOf(body);
    if (version != null && response.getHeaders().getFirst(HttpHeaders.ETAG) == null) {
      response.getHeaders().set(HttpHeaders.ETAG, ETags.format(version));
    }
    return body;
  }

  static Long versionOf(Object body) {
    if (body instanceof Versioned v) {
      return v.version();
    }
    if (body instanceof Map<?, ?> m && m.get("meta") instanceof Map<?, ?> meta) {
      return parse(meta.get("versionId"));
    }
    if (body instanceof JsonNode n) {
      JsonNode v = n.path("meta").path("versionId");
      return v.isNumber() || v.isString() ? parse(v.asString()) : null;
    }
    return null;
  }

  private static Long parse(Object versionId) {
    String s =
        versionId instanceof Number || versionId instanceof String
            ? versionId.toString().strip()
            : "";
    return s.matches("\\d{1,18}") ? Long.valueOf(s) : null;
  }
}
