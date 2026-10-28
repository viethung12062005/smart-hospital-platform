using System.Net;
using System.Text.Json;
using Xunit;

namespace Shp.Web.Tests;

public sealed class AuthTests : IAsyncLifetime
{
    private TestApp _app = null!;

    public async Task InitializeAsync() => _app = await TestApp.StartAsync();

    public async Task DisposeAsync() => await _app.DisposeAsync();

    private static async Task<JsonElement> Body(HttpResponseMessage r) => JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement;

    [Fact]
    public async Task Valid_linked_patient_reaches_endpoint_with_principal()
    {
        _app.Links.Link("sub-1", "Patient", "p-1");
        var r = await _app.GetAsync("/api/v1/me", TestApp.Token());
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        var b = await Body(r);
        Assert.Equal("p-1", b.GetProperty("fhirUserId").GetString());
        Assert.Equal("patient", b.GetProperty("roles")[0].GetString());
    }

    [Fact]
    public async Task Missing_token_is_401_problem_json()
    {
        var r = await _app.GetAsync("/api/v1/me");
        Assert.Equal(HttpStatusCode.Unauthorized, r.StatusCode);
        Assert.Equal("application/problem+json", r.Content.Headers.ContentType?.MediaType);
        Assert.Equal("unauthenticated", (await Body(r)).GetProperty("code").GetString());
        Assert.True(r.Headers.Contains("X-Request-Id"));
    }

    [Fact]
    public async Task Wrong_audience_wrong_signature_and_expired_tokens_are_401()
    {
        _app.Links.Link("sub-1", "Patient", "p-1");
        foreach (var token in new[]
                 {
                     TestApp.Token(audience: "other-api"),
                     TestApp.Token(key: TestApp.OtherKey()),
                     TestApp.Token(lifetime: TimeSpan.FromMinutes(-2)),
                 })
        {
            Assert.Equal(HttpStatusCode.Unauthorized, (await _app.GetAsync("/api/v1/me", token)).StatusCode);
        }
    }

    [Fact]
    public async Task Staff_without_mfa_is_rejected_and_with_mfa_accepted()
    {
        _app.Links.Link("doc", "Practitioner", "d-1");
        var noMfa = await _app.GetAsync("/api/v1/me", TestApp.Token(["doctor"], "doc"));
        Assert.Equal(HttpStatusCode.Unauthorized, noMfa.StatusCode);
        var mfa = await _app.GetAsync("/api/v1/me", TestApp.Token(["doctor"], "doc", amr: ["pwd", "mfa"]));
        Assert.Equal(HttpStatusCode.OK, mfa.StatusCode);
    }

    [Fact]
    public async Task Patient_does_not_need_mfa()
    {
        _app.Links.Link("sub-1", "Patient", "p-1");
        Assert.Equal(HttpStatusCode.OK, (await _app.GetAsync("/api/v1/me", TestApp.Token())).StatusCode);
    }

    [Fact]
    public async Task Mfa_can_be_disabled_for_local_realm()
    {
        await using var local = await TestApp.StartAsync(requireMfa: false);
        local.Links.Link("doc", "Practitioner", "d-1");
        Assert.Equal(HttpStatusCode.OK, (await local.GetAsync("/api/v1/me", TestApp.Token(["doctor"], "doc"))).StatusCode);
    }

    [Fact]
    public async Task Token_without_known_role_is_403()
    {
        _app.Links.Link("sub-1", "Patient", "p-1");
        var r = await _app.GetAsync("/api/v1/me", TestApp.Token(["superuser"]));
        Assert.Equal(HttpStatusCode.Forbidden, r.StatusCode);
        Assert.Equal("forbidden", (await Body(r)).GetProperty("code").GetString());
    }

    [Fact]
    public async Task Unlinked_account_is_403_except_on_registration()
    {
        Assert.Equal(HttpStatusCode.Forbidden, (await _app.GetAsync("/api/v1/me", TestApp.Token())).StatusCode);
        var reg = await _app.GetAsync("/api/v1/registration", TestApp.Token());
        Assert.Equal(HttpStatusCode.OK, reg.StatusCode);
        Assert.False((await Body(reg)).GetProperty("linked").GetBoolean());
    }

    [Fact]
    public async Task Disabled_account_is_403_even_on_registration()
    {
        _app.Links.Link("sub-1", "Patient", "p-1", disabled: true);
        Assert.Equal(HttpStatusCode.Forbidden, (await _app.GetAsync("/api/v1/me", TestApp.Token())).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await _app.GetAsync("/api/v1/registration", TestApp.Token())).StatusCode);
    }

    [Fact]
    public async Task Role_claim_is_case_insensitive_and_unknown_values_are_dropped()
    {
        _app.Links.Link("sub-1", "Patient", "p-1");
        var b = await Body(await _app.GetAsync("/api/v1/me", TestApp.Token(["PATIENT", "wizard"])));
        Assert.Single(b.GetProperty("roles").EnumerateArray());
    }
}
