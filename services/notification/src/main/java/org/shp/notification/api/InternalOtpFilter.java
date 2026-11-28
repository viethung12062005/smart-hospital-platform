package org.shp.notification.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.shp.events.CloudEvents;
import org.shp.notification.app.OtpService;
import org.shp.security.ShpJwt;
import org.shp.web.errors.ErrorCatalog;
import org.shp.web.errors.ErrorWriter;
import org.shp.web.errors.ShpException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /internal/v1/otp/send} (N8) and {@code /internal/v1/otp/verify} (N9). Like the other
 * internal APIs this is a servlet filter, not an MVC handler: the caller is a service, not a user,
 * and the shared user-identity interceptors would reject its token. Callers present a workload
 * token whose roles hold {@code internal.notification} and whose {@code azp} is an allowed {@code
 * svc-<caller>} client (15 section 7); the check is {@link ShpJwt#requireService}. The route has no
 * gateway entry. Neither the code nor the destination is ever logged or echoed.
 *
 * <p>The IP counted against the per-IP limit is {@code context.clientIp} of the request body, which
 * the calling service must fill with the end user's address. {@code X-Forwarded-For} is not read:
 * on a service-to-service call it names the caller pod, or nothing. Without {@code
 * context.clientIp} only the per-number limit applies.
 */
public final class InternalOtpFilter extends OncePerRequestFilter {
  static final String SEND = "/internal/v1/otp/send";
  static final String VERIFY = "/internal/v1/otp/verify";
  static final String SERVICE = "notification";

  private final OtpService otp;
  private final String[] callers;

  /**
   * @param allowedClients {@code azp} values of the caller clients
   */
  public InternalOtpFilter(OtpService otp, Set<String> allowedClients) {
    this.otp = otp;
    this.callers = allowedClients.toArray(String[]::new);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String uri = req.getRequestURI();
    return !SEND.equals(uri) && !VERIFY.equals(uri);
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
    if (!"POST".equals(req.getMethod())) {
      ErrorWriter.write(req, res, ErrorCatalog.get("validation-failed"), null, null);
      return;
    }
    try {
      JsonNode body = CloudEvents.JSON.readTree(req.getInputStream());
      Map<String, Object> out = new LinkedHashMap<>();
      if (SEND.equals(req.getRequestURI())) {
        var sent =
            otp.send(
                text(body, "purpose"),
                text(body, "channelHint"),
                text(body, "destination"),
                text(body, "locale"),
                clientIp(body));
        out.put("challengeId", sent.challengeId());
        out.put("channel", sent.channel());
        out.put("expiresAt", sent.expiresAt().toString());
      } else {
        out.put("ok", otp.verify(text(body, "challengeId"), text(body, "code")));
      }
      res.setStatus(200);
      res.setContentType("application/json");
      res.setCharacterEncoding("UTF-8");
      res.setHeader("Cache-Control", "no-store");
      res.getWriter().write(CloudEvents.JSON.writeValueAsString(out));
    } catch (ShpException e) {
      ErrorWriter.write(req, res, e.info(), e.detail(), e.retryAfter());
    } catch (RuntimeException e) {
      // A malformed body: say so without echoing it (it holds a code or a number).
      if (e instanceof tools.jackson.core.JacksonException) {
        ErrorWriter.write(req, res, ErrorCatalog.get("validation-failed"), null, null);
      } else {
        throw e;
      }
    }
  }

  private static String text(JsonNode body, String field) {
    JsonNode n = body == null ? null : body.get(field);
    return n == null || !n.isString() ? null : n.asString();
  }

  private static String clientIp(JsonNode body) {
    JsonNode ctx = body == null ? null : body.get("context");
    String fromBody = ctx == null ? null : text(ctx, "clientIp");
    return fromBody == null || fromBody.isBlank() ? null : fromBody;
  }
}
