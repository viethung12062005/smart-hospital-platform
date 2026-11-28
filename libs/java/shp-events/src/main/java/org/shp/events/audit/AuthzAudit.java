package org.shp.events.audit;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.shp.authz.AuthzDecision;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;

/**
 * Turn an {@link AuthzDecision} into an audit record: both allows and denials are recorded, denials
 * with a reason code.
 */
public final class AuthzAudit {
  /** Matrix action → audit action. A test ensures every matrix action has a mapping. */
  public static final Map<String, String> ACTION_MAP =
      Map.ofEntries(
          Map.entry("read", AuditActions.READ),
          Map.entry("search", AuditActions.SEARCH),
          Map.entry("create", AuditActions.CREATE),
          Map.entry("update", AuditActions.UPDATE),
          Map.entry("delete", AuditActions.DELETE),
          Map.entry("download", AuditActions.DOWNLOAD_URL),
          Map.entry("upload", AuditActions.CREATE),
          Map.entry("release", AuditActions.UPDATE),
          Map.entry("book", AuditActions.CREATE),
          Map.entry("cancel", AuditActions.UPDATE),
          Map.entry("block", AuditActions.UPDATE),
          Map.entry("merge", AuditActions.UPDATE),
          Map.entry("manage", AuditActions.UPDATE),
          Map.entry("review", AuditActions.UPDATE),
          Map.entry("run", AuditActions.UPDATE),
          Map.entry("restore", AuditActions.UPDATE),
          Map.entry("retry", AuditActions.UPDATE),
          Map.entry("ask", AuditActions.AI_QUERY),
          Map.entry("use", AuditActions.AI_QUERY),
          Map.entry("export", AuditActions.EXPORT),
          Map.entry("reveal", AuditActions.READ),
          Map.entry("start", AuditActions.BREAK_GLASS_START));

  /** Representative role when the caller has several: prefer the more privileged one. */
  private static final List<String> ROLE_PRIORITY =
      List.of("dpo", "admin", "doctor", "nurse", "patient");

  private AuthzAudit() {}

  /**
   * {@code errorOutcome} is non-null (usually {@code error}) when post-authorization processing
   * fails unexpectedly.
   */
  public static AuditRecord fromDecision(
      Clock clock,
      String service,
      Principal principal,
      String fhirUserType,
      String action,
      String resource,
      AuthzDecision decision,
      UUID patientId,
      String traceId,
      String clientIp,
      String userAgent,
      String errorOutcome) {
    return fromDecision(
        clock,
        service,
        principal,
        fhirUserType,
        action,
        resource,
        decision,
        patientId,
        traceId,
        clientIp,
        userAgent,
        errorOutcome,
        null);
  }

  /**
   * As above; {@code allowedReason} (at most {@link AuditRecordBuilder#MAX_REASON_LENGTH}
   * characters) is recorded on an allowed decision, for example a break-glass reason. A denial
   * always carries its deny reason code instead.
   */
  public static AuditRecord fromDecision(
      Clock clock,
      String service,
      Principal principal,
      String fhirUserType,
      String action,
      String resource,
      AuthzDecision decision,
      UUID patientId,
      String traceId,
      String clientIp,
      String userAgent,
      String errorOutcome,
      String allowedReason) {
    return fromDecision(
        clock,
        service,
        principal,
        fhirUserType,
        action,
        resource,
        decision,
        patientId,
        traceId,
        clientIp,
        userAgent,
        errorOutcome,
        allowedReason,
        null);
  }

  /**
   * As above, for a decision taken while the caller acts for a managed profile (ADR-0018): {@code
   * delegation} (from {@link ResourceContext#delegation(Principal)}) sets {@code onBehalfOf} to
   * {@code Patient/<id>} and {@code relationship} to guardian/proxy, on allows and denials alike;
   * the actor stays the signed-in account. Null means no delegation: neither key is written.
   */
  public static AuditRecord fromDecision(
      Clock clock,
      String service,
      Principal principal,
      String fhirUserType,
      String action,
      String resource,
      AuthzDecision decision,
      UUID patientId,
      String traceId,
      String clientIp,
      String userAgent,
      String errorOutcome,
      String allowedReason,
      ResourceContext.Delegation delegation) {
    String mapped = ACTION_MAP.get(action);
    if (mapped == null) {
      throw new IllegalArgumentException("Action has no audit mapping: " + action);
    }
    String role =
        ROLE_PRIORITY.stream().filter(principal.roles()::contains).findFirst().orElse("system");
    var actor =
        new AuditRecord.Actor(
            principal.subject(),
            role,
            principal.fhirUserId() == null ? null : fhirUserType + "/" + principal.fhirUserId());
    return AuditRecordBuilder.build(
        clock,
        service,
        mapped,
        decision.allowed()
            ? (errorOutcome != null ? errorOutcome : AuditActions.SUCCESS)
            : AuditActions.DENIED,
        actor,
        delegation == null ? null : "Patient/" + delegation.patientId(),
        delegation == null ? null : delegation.relationship(),
        resource,
        patientId,
        decision.purpose(),
        decision.allowed() ? allowedReason : decision.denyReason(),
        traceId,
        clientIp,
        userAgent);
  }
}
