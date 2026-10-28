using System.Globalization;
using System.Text;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using Shp.Authorization;
using Shp.Events.Audit;
using Shp.ServiceDefaults;
using Shp.Web.Errors;

namespace Shp.Web.Auth;

/// <summary>Khai báo quyền của một endpoint: (resource, action) trong ma trận và cách dựng <see cref="ResourceContext"/> từ request.</summary>
/// <param name="Resource">Tên resource trong matrix.yaml (ví dụ <c>Appointment</c>).</param>
/// <param name="Action">Hành động trong matrix.yaml (ví dụ <c>cancel</c>).</param>
/// <param name="ContextFactory">Tra dữ kiện về tài nguyên (chủ sở hữu, quan hệ điều trị, trạng thái...). Null = không có dữ kiện (chỉ rule không điều kiện mới khớp).</param>
/// <param name="DenyAsNotFound">true: từ chối trả 404 (không lộ sự tồn tại, theo 07 §4); audit vẫn ghi denied.</param>
public sealed record ShpAuthzMetadata(
    string Resource,
    string Action,
    Func<HttpContext, ValueTask<ResourceContext>>? ContextFactory,
    bool DenyAsNotFound);

/// <summary>Đánh dấu endpoint công khai có chủ đích (không cần phân quyền), ví dụ callback đăng nhập.</summary>
[AttributeUsage(AttributeTargets.Method | AttributeTargets.Class)]
public sealed class ShpPublicEndpointAttribute : Attribute;

public static class AuthzEndpointExtensions
{
    /// <summary>Đăng ký <see cref="AuthzMatrix"/> (nhúng), <see cref="AuthorizationEngine"/> và <see cref="TimeProvider"/>. Service phải tự đăng ký <see cref="IAuditSink"/>.</summary>
    public static IServiceCollection AddShpAuthorization(this IServiceCollection services)
    {
        ArgumentNullException.ThrowIfNull(services);
        services.AddSingleton(TimeProvider.System);
        services.AddSingleton(_ => AuthzMatrix.LoadEmbedded());
        services.AddSingleton<AuthorizationEngine>();
        return services;
    }

    /// <summary>
    /// Bắt buộc xác thực + phân quyền theo ma trận cho endpoint, và ghi audit cho MỌI quyết định (cho phép, từ chối) và lỗi khi xử lý.
    /// Từ chối: 403 <c>forbidden</c>/<c>no-care-relationship</c> (hoặc 404 nếu <paramref name="denyAsNotFound"/>).
    /// </summary>
    public static TBuilder RequireShpAuthz<TBuilder>(
        this TBuilder builder,
        string resource,
        string action,
        Func<HttpContext, ValueTask<ResourceContext>>? contextFactory = null,
        bool denyAsNotFound = false)
        where TBuilder : IEndpointConventionBuilder
    {
        ArgumentNullException.ThrowIfNull(builder);
        var meta = new ShpAuthzMetadata(resource, action, contextFactory, denyAsNotFound);
        builder.WithMetadata(meta);
        builder.Add(b => b.Metadata.Add(new Microsoft.AspNetCore.Authorization.AuthorizeAttribute()));
        builder.AddEndpointFilter(async (ctx, next) => await Enforce(ctx, next, meta).ConfigureAwait(false));
        return builder;
    }

    private static async ValueTask<object?> Enforce(EndpointFilterInvocationContext ctx, EndpointFilterDelegate next, ShpAuthzMetadata meta)
    {
        var http = ctx.HttpContext;
        var sp = http.RequestServices;
        var principal = http.GetShpPrincipal();
        var rctx = meta.ContextFactory is null ? new ResourceContext() : await meta.ContextFactory(http).ConfigureAwait(false);
        var decision = sp.GetRequiredService<AuthorizationEngine>().Decide(principal, meta.Resource, meta.Action, rctx);

        var sink = sp.GetRequiredService<IAuditSink>();
        var clock = sp.GetRequiredService<TimeProvider>();
        var service = sp.GetRequiredService<ServiceInfo>().Name;
        var resource = AuditResourceName(http, meta.Resource);
        Guid? patientId = Guid.TryParse(rctx.PatientId, out var g) ? g : null;
        var fhirType = principal.Roles.Contains("patient") ? "Patient" : "Practitioner";
        AuditRecord Record(string? errorOutcome = null) => AuthzAudit.FromDecision(
            clock, service, principal, fhirType, meta.Action, resource, decision, patientId,
            System.Diagnostics.Activity.Current?.TraceId.ToString(), http.Connection.RemoteIpAddress?.ToString(), http.Request.Headers.UserAgent.ToString(),
            errorOutcome);

        if (!decision.Allowed)
        {
            await sink.EmitAsync(Record(), http.RequestAborted).ConfigureAwait(false); // từ chối cũng phải có dấu vết (phát hiện dò quyền)
            throw new ShpException(meta.DenyAsNotFound ? "not-found" : decision.DenyReason == "no-care-relationship" ? "no-care-relationship" : "forbidden");
        }

        http.Items[ItemDecision] = decision;
        try
        {
            var result = await next(ctx).ConfigureAwait(false);
            await sink.EmitAsync(Record(), http.RequestAborted).ConfigureAwait(false);
            return result;
        }
        catch (ShpException)
        {
            // Lỗi nghiệp vụ lường trước (409, 422...): truy cập đã diễn ra, ghi nhận như bình thường.
            await sink.EmitAsync(Record(), http.RequestAborted).ConfigureAwait(false);
            throw;
        }
        catch (Exception)
        {
            await sink.EmitAsync(Record(AuditOutcomes.Error), CancellationToken.None).ConfigureAwait(false);
            throw;
        }
    }

    public const string ItemDecision = "shp.authz.decision";

    /// <summary>Quyết định phân quyền của request hiện tại (để handler áp <c>Fields</c>/<c>Hide</c>).</summary>
    public static AuthzDecision GetShpDecision(this HttpContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        return context.Items[ItemDecision] as AuthzDecision ?? throw new InvalidOperationException("Endpoint chưa dùng RequireShpAuthz");
    }

    /// <summary>Tên resource cho audit: <c>Loại/id</c> khi route có <c>{id}</c>, thêm TÊN tham số query (không bao giờ giá trị) cho tìm kiếm.</summary>
    internal static string AuditResourceName(HttpContext http, string resource)
    {
        var sb = new StringBuilder(resource);
        if (http.Request.RouteValues.TryGetValue("id", out var id) && Convert.ToString(id, CultureInfo.InvariantCulture) is { Length: > 0 and <= 64 } s
            && s.All(c => char.IsAsciiLetterOrDigit(c) || c == '-'))
        {
            sb.Append('/').Append(s);
        }

        var names = http.Request.Query.Keys.Where(k => k.All(c => char.IsAsciiLetterOrDigit(c) || c is '_' or '-' or '.' or ':' or ',')).Order(StringComparer.Ordinal).ToList();
        if (names.Count > 0)
        {
            sb.Append('?').AppendJoin('&', names);
        }

        return sb.ToString();
    }

    /// <summary>
    /// Endpoint chưa khai báo quyền (thiếu <c>RequireShpAuthz</c>, <c>[ShpPublicEndpoint]</c>) — trừ /health và /info.
    /// Dùng trong test của từng service: <c>Assert.Empty(EndpointAuthzCoverage.FindUnprotected(factory.Services))</c> để quên phân quyền làm CI đỏ.
    /// </summary>
    public static IReadOnlyList<string> FindUnprotectedEndpoints(this IServiceProvider services)
    {
        ArgumentNullException.ThrowIfNull(services);
        return [.. services.GetRequiredService<EndpointDataSource>().Endpoints
            .OfType<RouteEndpoint>()
            .Where(e => e.Metadata.GetMetadata<ShpAuthzMetadata>() is null
                        && e.Metadata.GetMetadata<ShpPublicEndpointAttribute>() is null
                        && !IsPlatform(e.RoutePattern.RawText))
            .Select(e => e.RoutePattern.RawText ?? e.DisplayName ?? "?")
            .Order(StringComparer.Ordinal)];
    }

    private static bool IsPlatform(string? route) =>
        route is not null && (route.StartsWith("/health", StringComparison.Ordinal) || route == "/info");
}
