package org.shp.scheduling.api;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.shp.events.audit.AuditActions;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.AuditRecordBuilder;
import org.shp.events.audit.AuditSink;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ErrorCatalog;
import org.shp.web.errors.ErrorWriter;
import org.shp.web.precondition.ETags;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * S18 {@code GET /internal/v1/appointments/{id}} (08-api-scheduling §5, 07-api-conventions §8,
 * 15-authorization §7): returns the full FHIR {@code Appointment} for trusted calling services
 * ({@code clinical} and {@code ml}).
 *
 * <p>Registered as a servlet filter (instead of an MVC controller) because callers authenticate
 * with workload-identity service tokens whose {@code roles} claim carries {@code
 * internal.scheduling} and {@code azp} carries {@code svc-<caller>}, with no user role or {@code
 * identity_links} row. User tokens and all other services receive {@code 403 forbidden}, and every
 * access is audited with {@code actor.role = "system"} and {@code purposeOfUse = "HOPERAT"}.
 */
public final class InternalAppointmentFilter extends OncePerRequestFilter {

  static final String PREFIX = "/internal/v1/appointments/";
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

  private final JdbcClient jdbc;
  private final AuditSink audit;
  private final Clock clock;
  private final String requiredRole;
  private final Set<String> allowedClients;

  public InternalAppointmentFilter(
      JdbcClient jdbc,
      AuditSink audit,
      Clock clock,
      String requiredRole,
      Set<String> allowedClients) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.requiredRole = Objects.requireNonNull(requiredRole, "requiredRole must not be null");
    this.allowedClients = Set.copyOf(Objects.requireNonNull(allowedClients, "allowedClients"));
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

    String subject = jwt.getToken().getSubject();
    List<String> roles = jwt.getToken().getClaimAsStringList("roles");
    String client = jwt.getToken().getClaimAsString("azp");

    String rawId =
        UriUtils.decode(req.getRequestURI().substring(PREFIX.length()), StandardCharsets.UTF_8);
    UUID appointmentId = parseUuid(rawId);

    boolean authorized =
        roles != null
            && roles.contains(requiredRole)
            && client != null
            && allowedClients.contains(client);
    if (!authorized) {
      UUID patientId = appointmentId == null ? null : findPatientId(appointmentId).orElse(null);
      emitAudit(
          req,
          subject,
          client,
          AuditActions.DENIED,
          auditResourceName(rawId),
          patientId,
          null,
          "role-not-allowed");
      ErrorWriter.write(req, res, ErrorCatalog.get("forbidden"), null, null);
      return;
    }

    if (!"GET".equals(req.getMethod())) {
      ErrorWriter.write(req, res, ErrorCatalog.get("validation-failed"), null, null);
      return;
    }

    if (appointmentId == null) {
      emitAudit(
          req,
          subject,
          client,
          AuditActions.SUCCESS,
          auditResourceName(rawId),
          null,
          "HOPERAT",
          null);
      ErrorWriter.write(req, res, ErrorCatalog.get("not-found"), null, null);
      return;
    }

    Optional<AppointmentPayload> found = findById(appointmentId);
    if (found.isEmpty()) {
      emitAudit(
          req,
          subject,
          client,
          AuditActions.SUCCESS,
          "Appointment/" + appointmentId,
          null,
          "HOPERAT",
          null);
      ErrorWriter.write(req, res, ErrorCatalog.get("not-found"), null, null);
      return;
    }

    AppointmentPayload row = found.get();
    emitAudit(
        req,
        subject,
        client,
        AuditActions.SUCCESS,
        "Appointment/" + appointmentId,
        row.patientId(),
        "HOPERAT",
        null);

    res.setStatus(HttpServletResponse.SC_OK);
    res.setContentType(FhirJson.MEDIA_TYPE);
    res.setCharacterEncoding("UTF-8");
    res.setHeader(HttpHeaders.ETAG, ETags.format(row.versionId()));
    res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    res.getWriter().write(row.content());
  }

  private Optional<UUID> findPatientId(UUID appointmentId) {
    return jdbc.sql("SELECT patient_id FROM scheduling.appointment WHERE id = ?")
        .param(appointmentId)
        .query((rs, rowNum) -> rs.getObject("patient_id", UUID.class))
        .optional();
  }

  private Optional<AppointmentPayload> findById(UUID appointmentId) {
    return jdbc.sql(
            """
            SELECT id, version_id, patient_id, content::text AS content
              FROM scheduling.appointment
             WHERE id = ?
            """)
        .param(appointmentId)
        .query((rs, rowNum) ->
            new AppointmentPayload(
                rs.getObject("id", UUID.class),
                rs.getInt("version_id"),
                rs.getObject("patient_id", UUID.class),
                rs.getString("content")))
        .optional();
  }

  private void emitAudit(
      HttpServletRequest req,
      String subject,
      String client,
      String outcome,
      String resource,
      UUID patientId,
      String purposeOfUse,
      String reason) {
    var span = Span.current().getSpanContext();
    String traceId = span.isValid() ? span.getTraceId() : null;
    audit.emit(
        AuditRecordBuilder.build(
            clock,
            "scheduling",
            AuditActions.READ,
            outcome,
            new AuditRecord.Actor(actorSubject(subject, client), "system", null),
            resource,
            patientId,
            purposeOfUse,
            reason,
            traceId,
            req.getRemoteAddr(),
            req.getHeader("User-Agent")));
  }

  private static String actorSubject(String subject, String client) {
    if (subject != null && !subject.isBlank()) {
      return subject;
    }
    if (client != null && !client.isBlank()) {
      return client;
    }
    return "system";
  }

  private static String auditResourceName(String rawId) {
    return rawId != null && SAFE_ID.matcher(rawId).matches()
        ? "Appointment/" + rawId
        : "Appointment";
  }

  private static UUID parseUuid(String rawId) {
    if (rawId == null || rawId.isEmpty() || rawId.contains("/")) {
      return null;
    }
    try {
      return UUID.fromString(rawId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private record AppointmentPayload(UUID id, int versionId, UUID patientId, String content) {}
}
