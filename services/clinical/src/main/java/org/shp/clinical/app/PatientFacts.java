package org.shp.clinical.app;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bson.Document;
import org.shp.authz.AuthzDecision;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.clinical.infra.PatientRepository;
import org.shp.events.audit.AuditSink;
import org.shp.events.audit.AuthzAudit;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Looks up the facts the authorization matrix needs about one Patient (15 §2): who owns it, whether
 * the doctor has a care relationship or a break-glass session, and whether the caller's account
 * reaches it through an active profile link. The matrix decides; this class only reports facts.
 *
 * <p>A patient asking for a record that is not theirs (and not linked) gets 404, never 403, so the
 * answer does not reveal that the record exists (15 §6). The denial is audited here because the
 * request ends before the authorization interceptor decides.
 */
public final class PatientFacts implements ResourceContextResolver {
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final List<String> STAFF = List.of("doctor", "nurse", "admin", "dpo");

  private final PatientRepository patients;
  private final MongoTemplate mongo;
  private final AuditSink audit;
  private final Clock clock;
  private final boolean nurseOnly;

  /**
   * @param nurseOnly the endpoint is reserved for nurses (version history, C7): care and
   *     break-glass facts are withheld, so every other role is denied by the matrix
   */
  public PatientFacts(
      PatientRepository patients,
      MongoTemplate mongo,
      AuditSink audit,
      Clock clock,
      boolean nurseOnly) {
    this.patients = patients;
    this.mongo = mongo;
    this.audit = audit;
    this.clock = clock;
    this.nurseOnly = nurseOnly;
  }

  @Override
  public ResourceContext resolve(
      HttpServletRequest request, Map<String, String> pathVariables, Principal principal) {
    String id = pathVariables.get("id");
    if (id == null || patients.find(id).isEmpty()) {
      throw new ShpException("not-found");
    }
    var facts = ResourceContext.builder();
    String user = principal.fhirUserId();
    boolean staff = STAFF.stream().anyMatch(principal.roles()::contains);
    boolean own = id.equals(user);
    if (!nurseOnly || !principal.roles().contains("patient")) {
      facts.patientId(id); // withheld from patients on nurse-only endpoints, so `own` cannot match
    }

    if (principal.roles().contains("doctor") && user != null && !nurseOnly) {
      facts.hasCareRelationship(hasCareRelationship(user, id));
      facts.hasActiveBreakGlass(hasBreakGlass(user, id));
    }
    if (!staff && !own) {
      Document link = activeLink(principal.subject(), id);
      if (link == null) {
        auditNotFound(request, principal, id);
        throw new ShpException("not-found");
      }
      String scope = link.getString("scope");
      facts.linkedProfile(List.of("booking", "records", "full").contains(scope));
      facts.linkedRecords(List.of("records", "full").contains(scope));
      facts.guardianLink("guardian".equals(link.getString("relationship")));
    }
    return facts.build();
  }

  private boolean hasCareRelationship(String practitionerId, String patientId) {
    return mongo.exists(
        Query.query(
            Criteria.where("_id")
                .is(practitionerId + "|" + patientId)
                .and("validUntil")
                .gt(Date.from(clock.instant()))),
        "care_relationships");
  }

  private boolean hasBreakGlass(String practitionerId, String patientId) {
    return mongo.exists(
        Query.query(
            Criteria.where("practitionerId")
                .is(practitionerId)
                .and("patientId")
                .is(patientId)
                .and("expiresAt")
                .gt(Date.from(clock.instant()))),
        "break_glass_sessions");
  }

  /** The account's active, unexpired link to the patient, or null. */
  private Document activeLink(String accountSub, String patientId) {
    Date now = Date.from(clock.instant());
    return mongo.findOne(
        Query.query(
            Criteria.where("accountSub")
                .is(accountSub)
                .and("patientId")
                .is(patientId)
                .and("status")
                .is("active")
                .orOperator(
                    Criteria.where("expiresAt").is(null),
                    Criteria.where("expiresAt").exists(false),
                    Criteria.where("expiresAt").gt(now))),
        Document.class,
        "profile_links");
  }

  private void auditNotFound(HttpServletRequest request, Principal principal, String id) {
    String action = "PUT".equals(request.getMethod()) ? "update" : "read";
    var span = Span.current().getSpanContext();
    String traceId = span.isValid() ? span.getTraceId() : null;
    String resource = SAFE_ID.matcher(id).matches() ? "Patient/" + id : "Patient";
    audit.emit(
        AuthzAudit.fromDecision(
            clock,
            "clinical",
            principal,
            AuthzAudit.fhirUserType(principal),
            action,
            resource,
            AuthzDecision.deny("not-own"),
            parseUuid(id),
            traceId,
            request.getRemoteAddr(),
            request.getHeader("User-Agent"),
            null));
  }

  private static UUID parseUuid(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
