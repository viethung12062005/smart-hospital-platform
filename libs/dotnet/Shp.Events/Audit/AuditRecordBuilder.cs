using System.Net;
using System.Net.Sockets;
using System.Text.RegularExpressions;

namespace Shp.Events.Audit;

/// <summary>Dựng bản ghi audit đúng quy tắc: che IP, rút gọn user-agent, chặn giá trị nhạy cảm trong <c>resource</c> và <c>reason</c>.</summary>
public static partial class AuditRecordBuilder
{
    public const int MaxReasonLength = 200;
    public const int MaxUserAgentLength = 120;

    public static AuditRecord Build(
        TimeProvider clock,
        string service,
        string action,
        string outcome,
        AuditActor actor,
        string resource,
        Guid? patientId = null,
        string? purposeOfUse = null,
        string? reason = null,
        string? traceId = null,
        string? clientIp = null,
        string? userAgent = null)
    {
        ArgumentNullException.ThrowIfNull(clock);
        ArgumentNullException.ThrowIfNull(actor);
        if (!AuditActions.All.Contains(action))
        {
            throw new ArgumentException($"action không hợp lệ: {action}", nameof(action));
        }

        if (!AuditOutcomes.All.Contains(outcome))
        {
            throw new ArgumentException($"outcome không hợp lệ: {outcome}", nameof(outcome));
        }

        if (!ResourcePattern().IsMatch(resource))
        {
            // Chỉ cho phép "Loại", "Loại/id" hoặc "Loại?tên-tham-số&..." — không bao giờ có giá trị tìm kiếm (có thể là PII).
            throw new ArgumentException("resource chỉ được chứa loại, id hoặc TÊN tham số tìm kiếm (không có giá trị)", nameof(resource));
        }

        if (reason is { Length: > MaxReasonLength })
        {
            throw new ArgumentException($"reason dài quá {MaxReasonLength} ký tự", nameof(reason));
        }

        var ip = MaskIp(clientIp);
        var ua = userAgent is null ? null : userAgent.Length > MaxUserAgentLength ? userAgent[..MaxUserAgentLength] : userAgent;
        return new AuditRecord
        {
            EventId = Guid.CreateVersion7(),
            OccurredAt = clock.GetUtcNow(),
            Service = service,
            Action = action,
            Outcome = outcome,
            Actor = actor,
            PatientId = patientId,
            Resource = resource,
            PurposeOfUse = purposeOfUse,
            Reason = reason,
            TraceId = traceId,
            Client = ip is null && ua is null ? null : new AuditClient(ip, ua),
        };
    }

    /// <summary>IPv4: che octet cuối (<c>10.1.2.x</c>). IPv6: giữ 3 nhóm đầu (<c>2001:db8:1::x</c>). Giá trị không phải IP → null (không lưu rác).</summary>
    public static string? MaskIp(string? ip)
    {
        if (!IPAddress.TryParse(ip, out var address))
        {
            return null;
        }

        if (address.IsIPv4MappedToIPv6)
        {
            address = address.MapToIPv4();
        }

        var bytes = address.GetAddressBytes();
        return address.AddressFamily switch
        {
            AddressFamily.InterNetwork => $"{bytes[0]}.{bytes[1]}.{bytes[2]}.x",
            AddressFamily.InterNetworkV6 => $"{bytes[0]:x2}{bytes[1]:x2}:{bytes[2]:x2}{bytes[3]:x2}:{bytes[4]:x2}{bytes[5]:x2}::x",
            _ => null,
        };
    }

    [GeneratedRegex(@"^[A-Za-z]+(/[A-Za-z0-9-]{1,64})?(\?[A-Za-z_:.,-]+(&[A-Za-z_:.,-]+)*)?$")]
    private static partial Regex ResourcePattern();
}
