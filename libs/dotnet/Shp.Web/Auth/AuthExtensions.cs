using System.Security.Claims;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.Tokens;
using Shp.Web.Errors;

namespace Shp.Web.Auth;

public static class AuthExtensions
{
    /// <summary>
    /// JWT Bearer cho Entra ID và Keycloak: kiểm chữ ký, iss, aud, exp; map claim <c>roles</c>; nhân viên phải có amr=mfa.
    /// Lỗi trả đúng định dạng ở 07-api-conventions (401 unauthenticated / 403 forbidden), không rò thông tin.
    /// </summary>
    public static IServiceCollection AddShpAuthentication(this IServiceCollection services, IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentNullException.ThrowIfNull(configuration);

        services.AddOptions<ShpAuthOptions>().Configure(o =>
        {
            configuration.GetSection(ShpAuthOptions.Section).Bind(o);
            o.Authority = FirstNonEmpty(o.Authority, configuration["OIDC_AUTHORITY"]);
            o.Audience = FirstNonEmpty(o.Audience, configuration["OIDC_AUDIENCE"]);
        }).Validate(o => o.Authority.Length > 0 && o.Audience.Length > 0, "Auth:Authority và Auth:Audience (hoặc OIDC_AUTHORITY, OIDC_AUDIENCE) là bắt buộc");

        services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer();
        services.AddOptions<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme)
            .Configure<Microsoft.Extensions.Options.IOptions<ShpAuthOptions>>((jwt, shp) =>
            {
                var o = shp.Value;
                jwt.Authority = o.Authority;
                jwt.RequireHttpsMetadata = o.RequireHttpsMetadata;
                jwt.MapInboundClaims = false; // giữ nguyên tên claim gốc (sub, roles, amr)
                jwt.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidateIssuer = true,
                    ValidateAudience = true,
                    ValidAudience = o.Audience,
                    ValidateLifetime = true,
                    RequireExpirationTime = true,
                    RequireSignedTokens = true,
                    ClockSkew = o.ClockSkew,
                    NameClaimType = "sub",
                    RoleClaimType = "roles",
                };
                jwt.Events = new JwtBearerEvents
                {
                    OnTokenValidated = ctx => EnforceMfa(ctx, o),
                    OnChallenge = async ctx =>
                    {
                        ctx.HandleResponse();
                        await ErrorWriter.WriteAsync(ctx.HttpContext, ErrorCatalog.Get("unauthenticated")).ConfigureAwait(false);
                    },
                    OnForbidden = ctx => ErrorWriter.WriteAsync(ctx.HttpContext, ErrorCatalog.Get("forbidden")),
                };
            });
        services.AddAuthorization();
        return services;
    }

    /// <summary>Role hợp lệ của người dùng (đã lọc bỏ giá trị lạ), không phân biệt hoa thường.</summary>
    public static IReadOnlyCollection<string> GetShpRoles(this ClaimsPrincipal user)
    {
        ArgumentNullException.ThrowIfNull(user);
        return [.. user.FindAll("roles").Select(c => c.Value.ToLowerInvariant()).Where(Roles.All.Contains).Distinct()];
    }

    private static Task EnforceMfa(TokenValidatedContext ctx, ShpAuthOptions options)
    {
        var principal = ctx.Principal;
        if (principal is null)
        {
            ctx.Fail("no principal");
            return Task.CompletedTask;
        }

        if (options.RequireMfaForStaff && principal.GetShpRoles().Any(Roles.Staff.Contains))
        {
            var hasMfa = principal.FindAll("amr").Any(c => string.Equals(c.Value, "mfa", StringComparison.OrdinalIgnoreCase));
            if (!hasMfa)
            {
                ctx.Fail("mfa-required"); // 401: cần đăng nhập lại với MFA
            }
        }

        return Task.CompletedTask;
    }

    private static string FirstNonEmpty(string current, string? fallback) => !string.IsNullOrWhiteSpace(current) ? current : fallback ?? "";
}
