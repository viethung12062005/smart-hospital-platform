namespace Shp.Web.Errors;

/// <summary>Lỗi nghiệp vụ/giao thức có mã chuẩn. Ném từ bất kỳ đâu; middleware đổi thành HTTP đúng quy ước. <paramref name="detail"/> không được chứa PII.</summary>
public sealed class ShpException(string code, string? detail = null) : Exception(detail ?? ErrorCatalog.Get(code).MessageEn)
{
    public ErrorInfo Info { get; } = ErrorCatalog.Get(code);

    /// <summary>Thông điệp riêng cho người dùng (thay cho thông điệp mặc định của mã), không chứa PII.</summary>
    public string? Detail { get; } = detail;

    public TimeSpan? RetryAfter { get; init; }
}
