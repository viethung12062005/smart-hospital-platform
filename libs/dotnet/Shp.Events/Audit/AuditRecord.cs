using System.Text.Json.Serialization;

namespace Shp.Events.Audit;

/// <summary>Giá trị hợp lệ của <c>action</c> (05-storage-audit-blob §1.1).</summary>
public static class AuditActions
{
    public const string Read = "read";
    public const string Search = "search";
    public const string Create = "create";
    public const string Update = "update";
    public const string Delete = "delete";
    public const string DownloadUrl = "download-url";
    public const string AiQuery = "ai-query";
    public const string AiSummary = "ai-summary";
    public const string BreakGlassStart = "break-glass-start";
    public const string Export = "export";
    public const string Login = "login";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.Ordinal)
    {
        Read, Search, Create, Update, Delete, DownloadUrl, AiQuery, AiSummary, BreakGlassStart, Export, Login,
    };
}

public static class AuditOutcomes
{
    public const string Success = "success";
    public const string Denied = "denied";
    public const string Error = "error";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(StringComparer.Ordinal) { Success, Denied, Error };
}

public sealed record AuditActor(string Subject, string Role, string? FhirUser = null);

public sealed record AuditClient(string? Ip, string? UserAgent);

/// <summary>Bản ghi audit — payload của event <c>shp.audit.recorded.v1</c>. KHÔNG chứa nội dung lâm sàng, tên, SĐT hay giá trị tìm kiếm.</summary>
public sealed record AuditRecord
{
    [JsonPropertyName("eventId")]
    public required Guid EventId { get; init; }

    [JsonPropertyName("occurredAt")]
    public required DateTimeOffset OccurredAt { get; init; }

    [JsonPropertyName("service")]
    public required string Service { get; init; }

    [JsonPropertyName("action")]
    public required string Action { get; init; }

    [JsonPropertyName("outcome")]
    public required string Outcome { get; init; }

    [JsonPropertyName("actor")]
    public required AuditActor Actor { get; init; }

    [JsonPropertyName("patientId")]
    public Guid? PatientId { get; init; }

    [JsonPropertyName("resource")]
    public required string Resource { get; init; }

    [JsonPropertyName("purposeOfUse")]
    public string? PurposeOfUse { get; init; }

    [JsonPropertyName("reason")]
    public string? Reason { get; init; }

    [JsonPropertyName("traceId")]
    public string? TraceId { get; init; }

    [JsonPropertyName("client")]
    public AuditClient? Client { get; init; }

    /// <summary>Đóng gói thành CloudEvent E11 (subject = <c>Audit/{eventId}</c>).</summary>
    public CloudEvent ToCloudEvent(TimeProvider? clock = null) =>
        CloudEvents.Create(Service, "shp.audit.recorded.v1", "Audit/" + EventId, this, clock, EventId);
}
