using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Shp.Authorization;
using Shp.Web.Errors;

namespace Shp.Web.Auth;

/// <summary>Gắn vào endpoint (ví dụ đăng ký C1) cho phép tài khoản đã xác thực nhưng CHƯA liên kết với Patient/Practitioner.</summary>
[AttributeUsage(AttributeTargets.Method | AttributeTargets.Class)]
public sealed class AllowUnlinkedIdentityAttribute : Attribute;

internal sealed class IdentityMiddleware(RequestDelegate next)
{
    public const string ItemKey = "shp.principal";

    public async Task InvokeAsync(HttpContext context, IdentityResolver resolver)
    {
        if (context.User.Identity?.IsAuthenticated != true)
        {
            await next(context).ConfigureAwait(false);
            return;
        }

        var subject = context.User.FindFirst("sub")?.Value;
        var roles = context.User.GetShpRoles();
        if (string.IsNullOrEmpty(subject) || roles.Count == 0)
        {
            await ErrorWriter.WriteAsync(context, ErrorCatalog.Get("forbidden")).ConfigureAwait(false); // lớp 2: thiếu role
            return;
        }

        var link = await resolver.ResolveAsync(subject, context.RequestAborted).ConfigureAwait(false);
        var allowUnlinked = context.GetEndpoint()?.Metadata.GetMetadata<AllowUnlinkedIdentityAttribute>() is not null;
        if (link is { Disabled: true } || (link is null && !allowUnlinked))
        {
            await ErrorWriter.WriteAsync(context, ErrorCatalog.Get("forbidden")).ConfigureAwait(false); // lớp 3
            return;
        }

        context.Items[ItemKey] = new Principal(subject, roles, link?.FhirUserId);
        await next(context).ConfigureAwait(false);
    }
}

public static class IdentityExtensions
{
    /// <summary>Đăng ký <see cref="IdentityResolver"/>; service cung cấp <see cref="IIdentityLinkClient"/> (clinical: đọc DB; còn lại: <see cref="HttpIdentityLinkClient"/>).</summary>
    public static IServiceCollection AddShpIdentity<TClient>(this IServiceCollection services)
        where TClient : class, IIdentityLinkClient
    {
        ArgumentNullException.ThrowIfNull(services);
        services.AddSingleton(TimeProvider.System);
        services.AddSingleton<IIdentityLinkClient, TClient>();
        services.AddSingleton<IdentityResolver>();
        return services;
    }

    /// <summary>Đặt SAU <c>UseAuthentication()</c> và <c>UseRouting()</c>.</summary>
    public static IApplicationBuilder UseShpIdentity(this IApplicationBuilder app) => app.UseMiddleware<IdentityMiddleware>();

    /// <summary>Người gọi đã xác thực và đã tra danh tính, dùng cho <c>AuthorizationEngine.Decide</c>. Ném 403 nếu middleware chưa chạy hoặc chưa xác thực.</summary>
    public static Principal GetShpPrincipal(this HttpContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        return context.Items[IdentityMiddleware.ItemKey] as Principal ?? throw new ShpException("unauthenticated");
    }
}
