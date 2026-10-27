using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using Shp.Web.Auth;
using Shp.Web.Errors;

namespace Shp.Web.Tests;

/// <summary>Ứng dụng thử nghiệm trong bộ nhớ: dùng đúng AddShpAuthentication/UseShpIdentity/UseShpErrors, IdP được thay bằng khóa ký cục bộ.</summary>
internal sealed class TestApp : IAsyncDisposable
{
    public const string Issuer = "https://idp.test/realms/shp";
    public const string Audience = "shp-api";
    private static readonly SymmetricSecurityKey Key = new(Encoding.UTF8.GetBytes("test-only-signing-key-32-bytes-minimum!!"));

    private readonly WebApplication _app;

    private TestApp(WebApplication app, HttpClient client, FakeLinkClient links)
    {
        _app = app;
        Client = client;
        Links = links;
    }

    public HttpClient Client { get; }

    public FakeLinkClient Links { get; }

    public static async Task<TestApp> StartAsync(bool requireMfa = true)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Configuration.AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["Auth:Authority"] = Issuer,
            ["Auth:Audience"] = Audience,
            ["Auth:RequireMfaForStaff"] = requireMfa ? "true" : "false",
        });
        var links = new FakeLinkClient();
        builder.Services.AddShpAuthentication(builder.Configuration);
        builder.Services.AddSingleton<IIdentityLinkClient>(links);
        builder.Services.AddSingleton(TimeProvider.System);
        builder.Services.AddSingleton<IdentityResolver>();
        builder.Services.PostConfigure<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme, o =>
        {
            o.Configuration = new OpenIdConnectConfiguration { Issuer = Issuer }; // không gọi discovery
            o.TokenValidationParameters.ValidIssuer = Issuer;
            o.TokenValidationParameters.IssuerSigningKey = Key;
        });

        var app = builder.Build();
        app.UseShpErrors();
        app.UseRouting();
        app.UseAuthentication();
        app.UseShpIdentity();
        app.UseAuthorization();
        app.MapGet("/api/v1/me", (HttpContext c) =>
        {
            var p = c.GetShpPrincipal();
            return Results.Ok(new { p.Subject, p.Roles, p.FhirUserId });
        }).RequireAuthorization();
        app.MapGet("/api/v1/registration", (HttpContext c) => Results.Ok(new { linked = c.GetShpPrincipal().FhirUserId is not null }))
            .RequireAuthorization().WithMetadata(new AllowUnlinkedIdentityAttribute());
        app.MapGet("/api/v1/slot", () => { throw new ShpException("slot-unavailable"); });
        app.MapGet("/api/v1/limited", () => { throw new ShpException("rate-limited") { RetryAfter = TimeSpan.FromSeconds(30) }; });
        app.MapGet("/fhir/Patient/x", () => { throw new ShpException("not-found"); });
        app.MapGet("/api/v1/boom", () => { throw new InvalidOperationException("secret patient Nguyen Van A 0901234567"); });
        await app.StartAsync().ConfigureAwait(false);
        return new TestApp(app, app.GetTestClient(), links);
    }

    public static string Token(
        string[]? roles = null,
        string subject = "sub-1",
        string[]? amr = null,
        string audience = Audience,
        TimeSpan? lifetime = null,
        SecurityKey? key = null)
    {
        var claims = new Dictionary<string, object> { ["sub"] = subject, ["roles"] = roles ?? ["patient"] };
        if (amr is not null)
        {
            claims["amr"] = amr;
        }

        var life = lifetime ?? TimeSpan.FromMinutes(10);
        var now = DateTime.UtcNow;
        var descriptor = new SecurityTokenDescriptor
        {
            Issuer = Issuer,
            Audience = audience,
            Claims = claims,
            NotBefore = life < TimeSpan.Zero ? now + life - TimeSpan.FromMinutes(5) : now,
            Expires = now + life,
            SigningCredentials = new SigningCredentials(key ?? Key, SecurityAlgorithms.HmacSha256),
        };
        return new JsonWebTokenHandler().CreateToken(descriptor);
    }

    public static SymmetricSecurityKey OtherKey() => new(Encoding.UTF8.GetBytes("another-signing-key-32-bytes-minimum!!!!"));

    public async Task<HttpResponseMessage> GetAsync(string path, string? token = null, string? language = null)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, path);
        if (token is not null)
        {
            request.Headers.Authorization = new("Bearer", token);
        }

        if (language is not null)
        {
            request.Headers.AcceptLanguage.ParseAdd(language);
        }

        return await Client.SendAsync(request).ConfigureAwait(false);
    }

    public async ValueTask DisposeAsync()
    {
        Client.Dispose();
        await _app.DisposeAsync().ConfigureAwait(false);
    }
}

internal sealed class FakeLinkClient : IIdentityLinkClient
{
    private readonly Dictionary<string, IdentityLink> _links = new(StringComparer.Ordinal);

    public int Calls { get; private set; }

    public void Link(string subject, string type, string id, bool disabled = false) => _links[subject] = new IdentityLink(type, id, disabled);

    public void Remove(string subject) => _links.Remove(subject);

    public Task<IdentityLink?> FindAsync(string subject, CancellationToken cancellationToken)
    {
        Calls++;
        return Task.FromResult(_links.GetValueOrDefault(subject));
    }
}

internal static class ClaimsExtensions
{
    public static ClaimsPrincipal WithRoles(params string[] roles) =>
        new(new ClaimsIdentity(roles.Select(r => new Claim("roles", r)), "test"));
}
