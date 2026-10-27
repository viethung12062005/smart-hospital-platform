namespace Shp.Web.Auth;

/// <summary>Cấu hình xác thực, đọc từ section <c>Auth</c> hoặc biến môi trường <c>OIDC_AUTHORITY</c>/<c>OIDC_AUDIENCE</c> (đã nạp sẵn bởi Helm values local).</summary>
public sealed class ShpAuthOptions
{
    public const string Section = "Auth";

    /// <summary>Issuer của IdP (Entra ID: https://login.microsoftonline.com/{tenant}/v2.0; Keycloak: http://.../realms/shp).</summary>
    public string Authority { get; set; } = "";

    /// <summary>Audience mà token phải nhắm tới (local: shp-api).</summary>
    public string Audience { get; set; } = "";

    /// <summary>Chỉ để false trên local (Keycloak http). Cloud luôn true.</summary>
    public bool RequireHttpsMetadata { get; set; } = true;

    /// <summary>Nhân viên (doctor/nurse/admin/dpo) phải có amr chứa "mfa" (NFR-SEC1). Tắt ở local vì Keycloak realm local chưa mô phỏng MFA (task LOC-02).</summary>
    public bool RequireMfaForStaff { get; set; } = true;

    /// <summary>Độ lệch đồng hồ cho phép khi kiểm hạn token.</summary>
    public TimeSpan ClockSkew { get; set; } = TimeSpan.FromSeconds(30);
}
