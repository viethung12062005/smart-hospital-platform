package org.shp.clinical.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.shp.clinical.app.IdentityAccounts;
import org.shp.clinical.domain.IdentityAccount;
import org.shp.events.CloudEvents;
import org.shp.security.ShpJwt;
import org.shp.web.errors.ErrorCatalog;
import org.shp.web.errors.ErrorWriter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * {@code GET /internal/v1/identity/{subject}} (09 §4): the lookup every other service makes through
 * {@code HttpIdentityLinkClient}. Returns {@code {fhirUser, roles, disabled}}, 404 when the subject
 * is not linked.
 *
 * <p>The caller is a service, not a user: it presents a workload-identity token whose {@code roles}
 * claim holds the role of the TARGET service, {@code internal.clinical}, and whose {@code azp} is a
 * known caller client {@code svc-<caller>} (15 §7). The token is validated by the shared security
 * filter chain (signature, issuer, audience, expiry) before this filter runs, and the caller check
 * is {@link ShpJwt#requireService}, the same guard every other {@code /internal} endpoint uses
 * (IAM-02), so there is no second copy of the rule here. This is a servlet filter and not an MVC
 * handler on purpose: the shared MVC interceptors resolve a USER identity from {@code sub} and
 * would reject a service token, which carries no user role. The route has no gateway entry (07 §1)
 * and no user-facing role can reach it.
 */
public final class InternalIdentityFilter extends OncePerRequestFilter {
  static final String PREFIX = "/internal/v1/identity/";

  /** Target service name in the {@code internal.<service>} role ({@code internal.clinical}). */
  static final String SERVICE = "clinical";

  private final IdentityAccounts accounts;
  private final String[] callers;

  /**
   * @param allowedClients {@code azp} values of the caller clients ({@code svc-<caller>})
   */
  public InternalIdentityFilter(IdentityAccounts accounts, Set<String> allowedClients) {
    this.accounts = accounts;
    this.callers = allowedClients.toArray(String[]::new);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !req.getRequestURI().startsWith(PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwt)) {
      ErrorWriter.write(req, res, ErrorCatalog.get("unauthenticated"), null, null);
      return;
    }
    try {
      ShpJwt.requireService(jwt.getToken(), SERVICE, callers);
    } catch (AccessDeniedException e) {
      ErrorWriter.write(req, res, ErrorCatalog.get("forbidden"), null, null);
      return;
    }
    if (!"GET".equals(req.getMethod())) {
      ErrorWriter.write(req, res, ErrorCatalog.get("validation-failed"), null, null);
      return;
    }
    String subject =
        UriUtils.decode(req.getRequestURI().substring(PREFIX.length()), StandardCharsets.UTF_8);
    var account = subject.isEmpty() || subject.contains("/") ? null : accounts.find(subject);
    if (account.isEmpty()) {
      ErrorWriter.write(req, res, ErrorCatalog.get("not-found"), null, null);
      return;
    }
    write(res, account.get());
  }

  private static void write(HttpServletResponse res, IdentityAccount a) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fhirUser", a.fhirUser());
    body.put("roles", a.roles());
    body.put("disabled", a.disabled());
    res.setStatus(200);
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");
    res.setHeader("Cache-Control", "no-store");
    res.getWriter().write(CloudEvents.JSON.writeValueAsString(body));
  }
}
