using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Json;
using System.Text.Json.Serialization;

namespace Shp.Web.Auth;

/// <summary>Liên kết tài khoản (OIDC sub) ↔ Patient/Practitioner, lưu ở clinical (`identity_links`).</summary>
/// <param name="FhirUserType">Patient hoặc Practitioner.</param>
/// <param name="FhirUserId">Id (không kèm loại).</param>
/// <param name="Disabled">Tài khoản bị khóa.</param>
public sealed record IdentityLink(string FhirUserType, string FhirUserId, bool Disabled);

/// <summary>Nguồn dữ liệu liên kết: clinical đọc DB, service khác gọi HTTP nội bộ (<see cref="HttpIdentityLinkClient"/>).</summary>
public interface IIdentityLinkClient
{
    /// <summary>null nếu chưa liên kết.</summary>
    Task<IdentityLink?> FindAsync(string subject, CancellationToken cancellationToken);
}

/// <summary>
/// Tra identity link có cache. Link tìm thấy giữ 60 giây (khóa tài khoản có hiệu lực ≤ 60 giây, AC-08.1); "chưa liên kết" chỉ giữ vài giây
/// để người vừa đăng ký (C1) dùng được ngay.
/// </summary>
public sealed class IdentityResolver(IIdentityLinkClient client, TimeProvider clock)
{
    public static readonly TimeSpan LinkTtl = TimeSpan.FromSeconds(60);
    public static readonly TimeSpan MissTtl = TimeSpan.FromSeconds(5);
    private const int MaxEntries = 10_000;

    private readonly ConcurrentDictionary<string, (IdentityLink? Link, DateTimeOffset Expires)> _cache = new(StringComparer.Ordinal);

    public async Task<IdentityLink?> ResolveAsync(string subject, CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrEmpty(subject);
        var now = clock.GetUtcNow();
        if (_cache.TryGetValue(subject, out var hit) && hit.Expires > now)
        {
            return hit.Link;
        }

        var link = await client.FindAsync(subject, cancellationToken).ConfigureAwait(false);
        if (_cache.Count >= MaxEntries)
        {
            _cache.Clear(); // đơn giản, an toàn: chỉ tốn thêm vài lần gọi lại
        }

        _cache[subject] = (link, now + (link is null ? MissTtl : LinkTtl));
        return link;
    }
}

/// <summary>Gọi <c>GET /internal/v1/identity/{subject}</c> của clinical (09-api-clinical). Token workload identity gắn bởi DelegatingHandler của service.</summary>
public sealed class HttpIdentityLinkClient(HttpClient http) : IIdentityLinkClient
{
    public async Task<IdentityLink?> FindAsync(string subject, CancellationToken cancellationToken)
    {
        using var response = await http.GetAsync(new Uri("/internal/v1/identity/" + Uri.EscapeDataString(subject), UriKind.Relative), cancellationToken)
            .ConfigureAwait(false);
        if (response.StatusCode == HttpStatusCode.NotFound)
        {
            return null;
        }

        response.EnsureSuccessStatusCode();
        var dto = await response.Content.ReadFromJsonAsync<IdentityDto>(cancellationToken).ConfigureAwait(false)
            ?? throw new InvalidOperationException("Phản hồi identity rỗng");
        var parts = dto.FhirUser.Split('/', 2);
        if (parts.Length != 2 || parts[0] is not ("Patient" or "Practitioner") || parts[1].Length == 0)
        {
            throw new InvalidOperationException("fhirUser không đúng dạng Patient/{id} hoặc Practitioner/{id}");
        }

        return new IdentityLink(parts[0], parts[1], dto.Disabled);
    }

    private sealed record IdentityDto([property: JsonPropertyName("fhirUser")] string FhirUser, [property: JsonPropertyName("disabled")] bool Disabled);
}
