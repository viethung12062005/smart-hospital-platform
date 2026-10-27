namespace Shp.Authorization;

/// <summary>Tên predicate dùng trong ma trận (khớp <c>predicates</c> trong matrix.yaml).</summary>
public static class Predicates
{
    public const string Own = "own";
    public const string SelfPractitioner = "self-practitioner";
    public const string Care = "care";
    public const string BreakGlass = "breakglass";
    public const string Released = "released";
    public const string EncounterFinished = "encounter-finished";
    public const string UnfinishedEncounter = "unfinished-encounter";
    public const string Before2h = "before-2h";
    public const string NotCare = "not-care";
}

/// <summary>Người gọi, lấy từ token đã xác thực và identity link (không bao giờ từ header do client gửi).</summary>
/// <param name="Subject">OIDC subject.</param>
/// <param name="Roles">Role từ claim <c>roles</c>.</param>
/// <param name="FhirUserId">Id của Patient hoặc Practitioner đã liên kết; null nếu chưa liên kết.</param>
public sealed record Principal(string Subject, IReadOnlyCollection<string> Roles, string? FhirUserId);

/// <summary>
/// Các sự kiện về tài nguyên đang được truy cập. Service tra cứu trước (quan hệ điều trị, break-glass...) rồi truyền vào;
/// engine là hàm thuần. Thông tin thiếu (null) luôn được coi là predicate KHÔNG thỏa (từ chối khi không chắc).
/// </summary>
public sealed record ResourceContext
{
    public string? PatientId { get; init; }

    public string? PractitionerId { get; init; }

    public bool HasCareRelationship { get; init; }

    public bool HasActiveBreakGlass { get; init; }

    public bool? ReleasedToPatient { get; init; }

    /// <summary>true = encounter đã finished, false = chưa finished, null = không liên quan/không biết.</summary>
    public bool? EncounterFinished { get; init; }

    public DateTimeOffset? AppointmentStart { get; init; }
}

/// <summary>Kết quả quyết định. Khi từ chối, <see cref="DenyReason"/> dùng làm <c>reason</c> trong audit (không trả cho client).</summary>
public sealed record AuthzDecision(
    bool Allowed,
    string? RuleId,
    string? Purpose,
    IReadOnlyList<string> Fields,
    IReadOnlyList<string> Hide,
    string? DenyReason)
{
    public static AuthzDecision Deny(string reason) => new(false, null, null, [], [], reason);
}
