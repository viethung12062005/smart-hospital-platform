package org.shp.events.audit;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.shp.authz.AuthzDecision;
import org.shp.authz.Principal;

/**
 * Chuyển một {@link AuthzDecision} thành bản ghi audit: cả cho phép và từ chối đều được ghi, từ
 * chối kèm mã lý do.
 */
public final class AuthzAudit {
  /**
   * Hành động trong ma trận → hành động audit. Test bảo đảm mọi hành động trong ma trận đều có ánh
   * xạ.
   */
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
          Map.entry("manage", AuditActions.UPDATE),
          Map.entry("review", AuditActions.UPDATE),
          Map.entry("run", AuditActions.UPDATE),
          Map.entry("restore", AuditActions.UPDATE),
          Map.entry("ask", AuditActions.AI_QUERY),
          Map.entry("use", AuditActions.AI_QUERY),
          Map.entry("export", AuditActions.EXPORT),
          Map.entry("start", AuditActions.BREAK_GLASS_START));

  /** Role đại diện khi người gọi có nhiều role: ưu tiên role có quyền nhiều hơn. */
  private static final List<String> ROLE_PRIORITY =
      List.of("dpo", "admin", "doctor", "nurse", "patient");

  private AuthzAudit() {}

  /**
   * {@code errorOutcome} khác null (thường là {@code error}) khi xử lý sau phân quyền thất bại
   * ngoài dự kiến.
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
    String mapped = ACTION_MAP.get(action);
    if (mapped == null) {
      throw new IllegalArgumentException("Hành động chưa ánh xạ audit: " + action);
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
        resource,
        patientId,
        decision.purpose(),
        decision.allowed() ? null : decision.denyReason(),
        traceId,
        clientIp,
        userAgent);
  }
}
