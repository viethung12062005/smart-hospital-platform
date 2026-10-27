using Shp.Authorization;

namespace Shp.Events.Audit;

/// <summary>Chuyển một <see cref="AuthzDecision"/> thành bản ghi audit (SHR-27): cả cho phép và từ chối đều được ghi, từ chối kèm mã lý do.</summary>
public static class AuthzAudit
{
    /// <summary>Hành động trong ma trận → hành động audit. Test bảo đảm mọi hành động trong ma trận đều có ánh xạ.</summary>
    public static readonly IReadOnlyDictionary<string, string> ActionMap = new Dictionary<string, string>(StringComparer.Ordinal)
    {
        ["read"] = AuditActions.Read,
        ["search"] = AuditActions.Search,
        ["create"] = AuditActions.Create,
        ["update"] = AuditActions.Update,
        ["delete"] = AuditActions.Delete,
        ["download"] = AuditActions.DownloadUrl,
        ["upload"] = AuditActions.Create,
        ["release"] = AuditActions.Update,
        ["book"] = AuditActions.Create,
        ["cancel"] = AuditActions.Update,
        ["block"] = AuditActions.Update,
        ["manage"] = AuditActions.Update,
        ["review"] = AuditActions.Update,
        ["run"] = AuditActions.Update,
        ["restore"] = AuditActions.Update,
        ["ask"] = AuditActions.AiQuery,
        ["use"] = AuditActions.AiQuery,
        ["export"] = AuditActions.Export,
        ["start"] = AuditActions.BreakGlassStart,
    };

    /// <summary>Role đại diện cho actor khi có nhiều role: ưu tiên role có quyền nhiều hơn để audit phản ánh đúng ngữ cảnh.</summary>
    private static readonly string[] RolePriority = ["dpo", "admin", "doctor", "nurse", "patient"];

    public static AuditRecord FromDecision(
        TimeProvider clock,
        string service,
        Principal principal,
        string fhirUserType,
        string action,
        string resource,
        AuthzDecision decision,
        Guid? patientId,
        string? traceId,
        string? clientIp,
        string? userAgent,
        string? errorOutcome = null)
    {
        ArgumentNullException.ThrowIfNull(principal);
        ArgumentNullException.ThrowIfNull(decision);
        var role = RolePriority.FirstOrDefault(principal.Roles.Contains) ?? "system";
        var actor = new AuditActor(principal.Subject, role, principal.FhirUserId is null ? null : $"{fhirUserType}/{principal.FhirUserId}");
        return AuditRecordBuilder.Build(
            clock,
            service,
            ActionMap.TryGetValue(action, out var mapped) ? mapped : throw new ArgumentException($"Hành động chưa ánh xạ audit: {action}", nameof(action)),
            decision.Allowed ? errorOutcome ?? AuditOutcomes.Success : AuditOutcomes.Denied,
            actor,
            resource,
            patientId,
            decision.Purpose,
            decision.Allowed ? null : decision.DenyReason,
            traceId,
            clientIp,
            userAgent);
    }
}
