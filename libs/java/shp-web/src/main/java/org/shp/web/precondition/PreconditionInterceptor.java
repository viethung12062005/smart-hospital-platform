package org.shp.web.precondition;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.shp.web.errors.ShpException;
import org.shp.web.idempotency.IdempotencyKeys;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Kiểm header bắt buộc (07 §3): {@code Idempotency-Key} theo {@link RequireIdempotencyKey}; {@code
 * If-Match} cho mọi PUT và theo {@link RequireIfMatch}. Chạy TRƯỚC phân quyền: request thiếu header
 * không tra dữ kiện tài nguyên và không sinh bản ghi audit "success" cho truy cập chưa diễn ra.
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
      req.setAttribute(ATTR_VERSION, ETags.parseIfMatch(req.getHeader(HttpHeaders.IF_MATCH)));
    }
    return true;
  }

  /** Version trong If-Match của request hiện tại (đã kiểm định dạng). */
  public static long expectedVersion(HttpServletRequest req) {
    if (req.getAttribute(ATTR_VERSION) instanceof Long v) {
      return v;
    }
    throw new IllegalStateException("Endpoint không bắt buộc If-Match (PUT hoặc @RequireIfMatch)");
  }

  /**
   * Version hiện tại của resource khác If-Match → 412 {@code version-conflict}. Store vẫn phải ghi
   * có điều kiện theo version (CAS) để chặn ghi đè đồng thời giữa lúc đọc và lúc ghi.
   */
  public static void checkVersion(HttpServletRequest req, long currentVersion) {
    if (expectedVersion(req) != currentVersion) {
      throw new ShpException("version-conflict");
    }
  }

  /** Idempotency-Key của request hiện tại (đã kiểm định dạng). */
  public static String idempotencyKey(HttpServletRequest req) {
    if (req.getAttribute(ATTR_KEY) instanceof String k) {
      return k;
    }
    throw new IllegalStateException("Endpoint chưa dùng @RequireIdempotencyKey");
  }
}
