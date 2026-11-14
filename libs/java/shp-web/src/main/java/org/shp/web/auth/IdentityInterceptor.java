package org.shp.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashSet;
import java.util.Optional;
import org.shp.authz.Principal;
import org.shp.security.ShpJwt;
import org.shp.web.errors.ShpException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Layers 2 + 3 of 15-authorization: missing valid role → 403; not linked or locked → 403 (except
 * the {@link AllowUnlinkedIdentity} endpoint, but a locked account is still 403). Result: {@link
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
      return true; // public endpoint: no caller
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

  /** The caller is authenticated and identity-resolved; throws 401 if not yet. */
  public static Principal principal(HttpServletRequest req) {
    if (req.getAttribute(ATTR) instanceof Principal p) {
      return p;
    }
    throw new ShpException("unauthenticated");
  }
}
