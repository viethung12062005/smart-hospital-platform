package org.shp.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashSet;
import java.util.Optional;
import org.shp.authz.Principal;
import org.shp.web.errors.ShpException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Lớp 2 + 3 của 15-authorization: thiếu role hợp lệ → 403; chưa liên kết hoặc bị khóa → 403 (trừ
 * endpoint {@link AllowUnlinkedIdentity}, nhưng tài khoản bị khóa vẫn 403). Kết quả: {@link
 * Principal} trong request.
 */
public final class IdentityInterceptor implements HandlerInterceptor {
  static final String ATTR = "shp.principal";

  private final IdentityResolver resolver;

  public IdentityInterceptor(IdentityResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    if (!(SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken jwt)) {
      return true; // endpoint công khai: không có người gọi
    }
    String subject = jwt.getToken().getSubject();
    var roles = new HashSet<>(ShpJwt.roles(jwt.getToken()));
    if (subject == null || subject.isEmpty() || roles.isEmpty()) {
      throw new ShpException("forbidden");
    }
    Optional<IdentityLink> link = resolver.resolve(subject);
    boolean allowUnlinked =
        handler instanceof HandlerMethod hm
            && (hm.hasMethodAnnotation(AllowUnlinkedIdentity.class)
                || hm.getBeanType().isAnnotationPresent(AllowUnlinkedIdentity.class));
    if (link.map(IdentityLink::disabled).orElse(false) || (link.isEmpty() && !allowUnlinked)) {
      throw new ShpException("forbidden");
    }
    req.setAttribute(
        ATTR, new Principal(subject, roles, link.map(IdentityLink::fhirUserId).orElse(null)));
    return true;
  }

  /** Người gọi đã xác thực và đã tra danh tính; ném 401 nếu chưa có. */
  public static Principal principal(HttpServletRequest req) {
    if (req.getAttribute(ATTR) instanceof Principal p) {
      return p;
    }
    throw new ShpException("unauthenticated");
  }
}
