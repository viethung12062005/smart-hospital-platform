package org.shp.web.precondition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import org.shp.web.errors.ShpException;
import org.shp.web.idempotency.IdempotencyKeys;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Check required headers (07 §3): {@code Idempotency-Key} per {@link RequireIdempotencyKey}; {@code
 * If-Match} for every PUT and per {@link RequireIfMatch}. Runs BEFORE authorization: a request
 * missing a header does not look up resource facts and does not emit an audit "success" record for
 * an access that never happened.
 */
public final class PreconditionInterceptor implements HandlerInterceptor {
  private static final String ATTR_VERSION = "shp.precondition.version";
  private static final String ATTR_KEY = "shp.precondition.idempotencyKey";

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    if (!(handler instanceof HandlerMethod hm)) {
      return true;
    }
    if (hm.hasMethodAnnotation(RequireIdempotencyKey.class)) {
      req.setAttribute(ATTR_KEY, IdempotencyKeys.require(req.getHeader(IdempotencyKeys.HEADER)));
    }
    if (HttpMethod.PUT.matches(req.getMethod()) || hm.hasMethodAnnotation(RequireIfMatch.class)) {
      // Multiple If-Match lines are equivalent to a comma-separated list → also 412, do not
      // silently take
      // the first line (getHeader returns only the first value, distorting the "matches one of the
      // versions" semantics).
      var values = Collections.list(req.getHeaders(HttpHeaders.IF_MATCH));
      if (values.size() > 1) {
        throw new ShpException("version-conflict");
      }
      req.setAttribute(
          ATTR_VERSION, ETags.parseIfMatch(values.isEmpty() ? null : values.getFirst()));
    }
    return true;
  }

  /** Version in the current request's If-Match (format already validated). */
  public static long expectedVersion(HttpServletRequest req) {
    if (req.getAttribute(ATTR_VERSION) instanceof Long v) {
      return v;
    }
    throw new IllegalStateException("Endpoint does not require If-Match (PUT or @RequireIfMatch)");
  }

  /**
   * Current resource version differs from If-Match → 412 {@code version-conflict}. The store must
   * still write conditionally by version (CAS) to block concurrent overwrites between read and
   * write.
   */
  public static void checkVersion(HttpServletRequest req, long currentVersion) {
    if (expectedVersion(req) != currentVersion) {
      throw new ShpException("version-conflict");
    }
  }

  /** Idempotency-Key of the current request (format already validated). */
  public static String idempotencyKey(HttpServletRequest req) {
    if (req.getAttribute(ATTR_KEY) instanceof String k) {
      return k;
    }
    throw new IllegalStateException("Endpoint does not use @RequireIdempotencyKey");
  }
}
