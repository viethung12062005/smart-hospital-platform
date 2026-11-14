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
 * Set {@code ETag: W/"<versionId>"} on every response returning a resource (07 §3): FHIR resources
 * from {@code meta.versionId} (Map or JsonNode), DTOs from {@link Versioned}. Does not overwrite an
 * ETag the handler already set; lists, Bundles and bodies without a version are skipped.
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
      // Drop the ETag if the version cannot be read back with If-Match (negative or too long).
      return ETags.tryParseVersion(v.version());
    }
    if (body instanceof Map<?, ?> m) {
      return versionFromMeta(m.get("meta"));
    }
    if (body instanceof JsonNode n) {
      return versionFromMeta(n.path("meta"));
    }
    return null;
  }

  /**
   * {@code meta} may be a Map (built by hand) or JsonNode (read by Jackson); both must be accepted.
   */
  private static Long versionFromMeta(Object meta) {
    if (meta instanceof Map<?, ?> m) {
      return ETags.tryParseVersion(m.get("versionId"));
    }
    if (meta instanceof JsonNode n) {
      JsonNode v = n.path("versionId");
      return v.isNumber() || v.isString() ? ETags.tryParseVersion(v.asString()) : null;
    }
    return null;
  }
}
