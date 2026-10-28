package org.shp.web.authz;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.shp.authz.AuthorizationEngine;
import org.shp.authz.AuthzDecision;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.events.audit.AuditActions;
import org.shp.events.audit.AuditSink;
import org.shp.events.audit.AuthzAudit;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Thực thi {@link RequireShpAuthz}. Từ chối → audit {@code denied} + lý do → 403 {@code
 * forbidden}/{@code no-care-relationship} (hoặc 404). Cho phép → chạy handler; sau đó audit {@code
 * success} (kể cả lỗi nghiệp vụ 4xx) hoặc {@code error} (5xx).
 */
public final class AuthzInterceptor implements HandlerInterceptor {
  static final String ATTR_DECISION = "shp.authz.decision";
  private static final String ATTR_PENDING = "shp.authz.pending";

  private final AuthorizationEngine engine;
  private final AuditSink sink;
  private final Clock clock;
  private final String service;
  private final BeanFactory beans;

  public AuthzInterceptor(
      AuthorizationEngine engine, AuditSink sink, Clock clock, String service, BeanFactory beans) {
    this.engine = engine;
    this.sink = sink;
    this.clock = clock;
    this.service = service;
    this.beans = beans;
  }

  private record Pending(
      Principal principal,
      RequireShpAuthz meta,
      AuthzDecision decision,
      ResourceContext ctx,
      String resource) {}

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    if (!(handler instanceof HandlerMethod hm)) {
      return true;
    }
    RequireShpAuthz meta = hm.getMethodAnnotation(RequireShpAuthz.class);
    if (meta == null) {
      return true;
    }
    Principal principal = IdentityInterceptor.principal(req);
    Map<String, String> vars = pathVariables(req);
    ResourceContext ctx =
        meta.context().isEmpty()
            ? ResourceContext.empty()
            : beans
                .getBean(meta.context(), ResourceContextResolver.class)
                .resolve(req, vars, principal);
    AuthzDecision decision = engine.decide(principal, meta.resource(), meta.action(), ctx);
    var pending =
        new Pending(principal, meta, decision, ctx, auditResourceName(meta.resource(), vars, req));
    if (!decision.allowed()) {
      emit(req, pending, null); // từ chối cũng phải có dấu vết (phát hiện dò quyền)
      throw new ShpException(
          meta.denyAsNotFound()
              ? "not-found"
              : "no-care-relationship".equals(decision.denyReason())
                  ? "no-care-relationship"
                  : "forbidden");
    }
    req.setAttribute(ATTR_DECISION, decision);
    req.setAttribute(ATTR_PENDING, pending);
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
    if (req.getAttribute(ATTR_PENDING) instanceof Pending pending) {
      // 4xx sau khi đã cho phép là lỗi nghiệp vụ lường trước: truy cập đã diễn ra → success. 5xx →
      // error.
      emit(req, pending, ex != null || res.getStatus() >= 500 ? AuditActions.ERROR : null);
    }
  }

  private void emit(HttpServletRequest req, Pending p, String errorOutcome) {
    UUID patientId = parseUuid(p.ctx().patientId());
    var ctx = Span.current().getSpanContext();
    sink.emit(
        AuthzAudit.fromDecision(
            clock,
            service,
            p.principal(),
            p.principal().roles().contains("patient") ? "Patient" : "Practitioner",
            p.meta().action(),
            p.resource(),
            p.decision(),
            patientId,
            ctx.isValid() ? ctx.getTraceId() : null,
            req.getRemoteAddr(),
            req.getHeader("User-Agent"),
            errorOutcome));
  }

  /** Quyết định của request hiện tại (để handler áp {@code fields}/{@code hide}). */
  public static AuthzDecision decision(HttpServletRequest req) {
    if (req.getAttribute(ATTR_DECISION) instanceof AuthzDecision d) {
      return d;
    }
    throw new IllegalStateException("Endpoint chưa dùng @RequireShpAuthz");
  }

  /**
   * {@code Loại/id} khi route có {@code {id}}; thêm TÊN tham số query (không bao giờ giá trị) cho
   * tìm kiếm.
   */
  static String auditResourceName(
      String resource, Map<String, String> vars, HttpServletRequest req) {
    var sb = new StringBuilder(resource);
    String id = vars.get("id");
    if (id != null
        && !id.isEmpty()
        && id.length() <= 64
        && id.chars().allMatch(c -> c < 128 && (Character.isLetterOrDigit(c) || c == '-'))) {
      sb.append('/').append(id);
    }
    var names = new TreeSet<String>();
    String query = req.getQueryString();
    if (query != null) {
      for (String pair : query.split("&")) {
        String name =
            java.net.URLDecoder.decode(
                pair.split("=", 2)[0], java.nio.charset.StandardCharsets.UTF_8);
        if (!name.isEmpty()
            && name.chars()
                .allMatch(
                    c -> c < 128 && (Character.isLetterOrDigit(c) || "_-.:,".indexOf(c) >= 0))) {
          names.add(name);
        }
      }
    }
    if (!names.isEmpty()) {
      sb.append('?').append(String.join("&", names));
    }
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> pathVariables(HttpServletRequest req) {
    Object v = req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    return v instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
  }

  private static UUID parseUuid(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
