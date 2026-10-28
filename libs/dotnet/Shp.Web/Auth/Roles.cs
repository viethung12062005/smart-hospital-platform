using System.Collections.Frozen;

namespace Shp.Web.Auth;

/// <summary>Các role hợp lệ (khớp contracts/authz/matrix.yaml).</summary>
public static class Roles
{
    public const string Patient = "patient";
    public const string Doctor = "doctor";
    public const string Nurse = "nurse";
    public const string Admin = "admin";
    public const string Dpo = "dpo";

    public static readonly FrozenSet<string> All = new[] { Patient, Doctor, Nurse, Admin, Dpo }.ToFrozenSet();

    /// <summary>Role của nhân viên: bắt buộc MFA.</summary>
    public static readonly FrozenSet<string> Staff = new[] { Doctor, Nurse, Admin, Dpo }.ToFrozenSet();
}
