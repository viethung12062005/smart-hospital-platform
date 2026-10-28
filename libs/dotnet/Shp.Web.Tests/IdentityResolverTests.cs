using System.Net;
using System.Text;
using Shp.Web.Auth;
using Xunit;

namespace Shp.Web.Tests;

public sealed class IdentityResolverTests
{
    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = new(2026, 11, 20, 8, 0, 0, TimeSpan.Zero);

        public override DateTimeOffset GetUtcNow() => Now;
    }

    [Fact]
    public async Task Link_is_cached_for_sixty_seconds_then_refreshed()
    {
        var client = new FakeLinkClient();
        client.Link("s", "Patient", "p1");
        var clock = new Clock();
        var resolver = new IdentityResolver(client, clock);

        await resolver.ResolveAsync("s", default);
        clock.Now += TimeSpan.FromSeconds(59);
        await resolver.ResolveAsync("s", default);
        Assert.Equal(1, client.Calls);

        client.Link("s", "Patient", "p1", disabled: true); // admin khóa tài khoản
        clock.Now += TimeSpan.FromSeconds(2);
        var after = await resolver.ResolveAsync("s", default);
        Assert.Equal(2, client.Calls);
        Assert.True(after!.Disabled); // có hiệu lực ngay khi hết cache (≤ 60 giây, AC-08.1)
    }

    [Fact]
    public async Task Missing_link_is_cached_only_briefly()
    {
        var client = new FakeLinkClient();
        var clock = new Clock();
        var resolver = new IdentityResolver(client, clock);

        Assert.Null(await resolver.ResolveAsync("s", default));
        client.Link("s", "Patient", "p1"); // vừa đăng ký xong
        clock.Now += TimeSpan.FromSeconds(6);
        Assert.NotNull(await resolver.ResolveAsync("s", default));
    }

    [Fact]
    public async Task Http_client_maps_responses()
    {
        using var ok = new HttpClient(new Stub(HttpStatusCode.OK, """{"fhirUser":"Practitioner/abc","roles":["doctor"],"disabled":false}""")) { BaseAddress = new Uri("http://clinical") };
        var link = await new HttpIdentityLinkClient(ok).FindAsync("kc|user", default);
        Assert.Equal(new IdentityLink("Practitioner", "abc", false), link);

        using var missing = new HttpClient(new Stub(HttpStatusCode.NotFound, "")) { BaseAddress = new Uri("http://clinical") };
        Assert.Null(await new HttpIdentityLinkClient(missing).FindAsync("x", default));

        using var bad = new HttpClient(new Stub(HttpStatusCode.OK, """{"fhirUser":"Device/1","disabled":false}""")) { BaseAddress = new Uri("http://clinical") };
        await Assert.ThrowsAsync<InvalidOperationException>(() => new HttpIdentityLinkClient(bad).FindAsync("x", default));

        using var down = new HttpClient(new Stub(HttpStatusCode.ServiceUnavailable, "")) { BaseAddress = new Uri("http://clinical") };
        await Assert.ThrowsAsync<HttpRequestException>(() => new HttpIdentityLinkClient(down).FindAsync("x", default));
    }

    private sealed class Stub(HttpStatusCode code, string body) : HttpMessageHandler
    {
        public string? LastPath { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastPath = request.RequestUri?.OriginalString;
            return Task.FromResult(new HttpResponseMessage(code) { Content = new StringContent(body, Encoding.UTF8, "application/json") });
        }
    }
}
