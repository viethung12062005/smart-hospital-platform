package org.shp.clinical.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.shp.clinical.app.ClinicalReads.RoleView;
import org.shp.clinical.app.InternalLookups;
import org.shp.events.CloudEvents;
import org.shp.events.audit.AuditActions;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.AuditRecordBuilder;
import org.shp.events.audit.AuditSink;
import org.shp.web.errors.ErrorCatalog;
import org.shp.web.errors.ErrorWriter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The internal lookups of 09 §4 other than the identity lookup (CLN-11): care check, practitioner
 * roles, patient contact and age band. Like {@code InternalIdentityFilter}, the caller is a service
 * with a workload-identity token ({@code roles} holds {@code internal.clinical}, {@code azp} =
 * {@code svc-<caller>}, 15 §7), already validated by the security filter chain; each route has its
 * own list of allowed callers. The contact route is the only one that returns contact data: every
 * call, allowed or denied, writes a {@code read} audit record.
 */
public final class InternalLookupFilter extends OncePerRequestFilter {

  /** The routes, with the callers 09 §4 names for each. */
  public enum Route {
    CARE("^/internal/v1/care-relationships/check$"),
    ROLES("^/internal/v1/practitioners/([^/]+)/roles$"),
    CONTACT("^/internal/v1/patients/([^/]+)/contact$"),
    AGE_BAND("^/internal/v1/patients/([^/]+)/age-band$");

    private final Pattern path;

    Route(String path) {
      this.path = Pattern.compile(path);
    }
  }

  /** FHIR id (01 §1); anything else cannot exist and is answered with 404. */
  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  static final String SERVICE = "clinical";

  private final InternalLookups lookups;
  private final AuditSink audit;
  private final Clock clock;
  private final String requiredRole;
  private final Map<Route, Set<String>> allowedClients;

  /**
   * @param requiredRole role every caller holds for this target ({@code internal.clinical})
   * @param allowedClients per route, the {@code azp} values of the allowed caller clients
   */
  public InternalLookupFilter(
      InternalLookups lookups,
      AuditSink audit,
      Clock clock,
      String requiredRole,
      Map<Route, Set<String>> allowedClients) {
    this.lookups = lookups;
    this.audit = audit;
    this.clock = clock;
    this.requiredRole = requiredRole;
    this.allowedClients = new EnumMap<>(Route.class);
    for (Route r : Route.values()) {
      this.allowedClients.put(r, Set.copyOf(allowedClients.getOrDefault(r, Set.of())));
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return match(req.getRequestURI()) == null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException {
    Hit hit = match(req.getRequestURI());
    Route route = hit.route();
    String id = hit.id();

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken jwt)) {
      ErrorWriter.write(req, res, ErrorCatalog.get("unauthenticated"), null, null);
      return;
    }
    boolean allowed = allowed(jwt.getToken(), route);
    if (route == Route.CONTACT) {
      auditContact(jwt.getToken(), id, allowed, req);
    }
    if (!allowed) {
      ErrorWriter.write(req, res, ErrorCatalog.get("forbidden"), null, null);
      return;
    }
    if (!"GET".equals(req.getMethod())) {
      ErrorWriter.write(req, res, ErrorCatalog.get("validation-failed"), null, null);
      return;
    }
    switch (route) {
      case CARE -> care(req, res);
      case ROLES -> roles(id, req, res);
      case CONTACT -> contact(id, req, res);
      case AGE_BAND -> ageBand(id, req, res);
    }
  }

  private void care(HttpServletRequest req, HttpServletResponse res) throws IOException {
    String practitioner = req.getParameter("practitioner");
    String patient = req.getParameter("patient");
    if (!isId(practitioner) || !isId(patient)) {
      ErrorWriter.write(
          req,
          res,
          ErrorCatalog.get("validation-failed"),
          "practitioner and patient are required ids.",
          null);
      return;
    }
    var check = lookups.care(practitioner, patient);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("care", check.care());
    if (check.care()) {
      body.put("via", check.via());
      body.put("validUntil", check.validUntil().toString());
    }
    ok(res, body);
  }

  private void roles(String id, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    var roles = isId(id) ? lookups.roles(id) : Optional.<List<RoleView>>empty();
    if (roles.isEmpty()) {
      ErrorWriter.write(req, res, ErrorCatalog.get("not-found"), null, null);
      return;
    }
    List<Map<String, Object>> body =
        roles.get().stream()
            .map(r -> {
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("id", r.id());
              item.put("specialty", r.specialty());
              item.put("code", r.code());
              item.put("active", r.active());
              return item;
            })
            .toList();
    ok(res, body);
  }

  private void contact(String id, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    var contact = isId(id) ? lookups.contact(id) : Optional.<InternalLookups.Contact>empty();
    if (contact.isEmpty()) {
      ErrorWriter.write(req, res, ErrorCatalog.get("not-found"), null, null);
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("phone", contact.get().phone());
    body.put("email", contact.get().email());
    body.put("locale", contact.get().locale());
    ok(res, body);
  }

  private void ageBand(String id, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    var band = isId(id) ? lookups.ageBand(id) : Optional.<String>empty();
    if (band.isEmpty()) {
      ErrorWriter.write(req, res, ErrorCatalog.get("not-found"), null, null);
      return;
    }
    ok(res, Map.of("ageBand", band.get()));
  }

  private boolean allowed(Jwt token, Route route) {
    List<String> roles = token.getClaimAsStringList("roles");
    String client = token.getClaimAsString("azp");
    return roles != null
        && roles.contains(requiredRole)
        && client != null
        && allowedClients.get(route).contains(client);
  }

  /**
   * 09 §4: every contact read is audited. Actor = the calling service account ({@code role:
   * system}); the record names the Patient, never the returned values.
   */
  private void auditContact(Jwt token, String id, boolean allowed, HttpServletRequest req) {
    String subject = token.getSubject() != null ? token.getSubject() : "unknown";
    UUID patientId = null;
    String resource = "Patient";
    if (isId(id)) {
      resource = "Patient/" + id;
      try {
        patientId = UUID.fromString(id);
      } catch (IllegalArgumentException e) {
        patientId = null; // not a UUID: the patient cannot exist, the record still names the call
      }
    }
    audit.emit(
        AuditRecordBuilder.build(
            clock,
            SERVICE,
            AuditActions.READ,
            allowed ? AuditActions.SUCCESS : AuditActions.DENIED,
            new AuditRecord.Actor(subject, "system", null),
            resource,
            patientId,
            "HOPERAT",
            null,
            null,
            req.getRemoteAddr(),
            req.getHeader("User-Agent")));
  }

  /** A matched route and its path id ({@code null} for the care check). */
  record Hit(Route route, String id) {}

  static Hit match(String uri) {
    for (Route r : Route.values()) {
      Matcher m = r.path.matcher(uri);
      if (m.matches()) {
        return new Hit(r, m.groupCount() > 0 ? m.group(1) : null);
      }
    }
    return null;
  }

  private static boolean isId(String id) {
    return id != null && ID.matcher(id).matches();
  }

  private static void ok(HttpServletResponse res, Object body) throws IOException {
    res.setStatus(200);
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");
    res.setHeader("Cache-Control", "no-store");
    res.getWriter().write(CloudEvents.JSON.writeValueAsString(body));
  }
}
