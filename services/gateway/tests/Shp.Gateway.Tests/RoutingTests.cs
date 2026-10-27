using System.Net;
using Microsoft.AspNetCore.Mvc.Testing;
using Xunit;

namespace Shp.Gateway.Tests;

public sealed class RoutingTests(WebApplicationFactory<Program> factory) : IClassFixture<WebApplicationFactory<Program>>
{
    [Theory]
    [InlineData("/internal/identity-links/abc")]
    [InlineData("/api/v2/unknown")]
    public async Task Unrouted_paths_are_not_exposed(string path)
    {
        using var client = factory.CreateClient();
        using var response = await client.GetAsync(new Uri(path, UriKind.Relative));
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task Routed_path_is_forwarded_to_backend()
    {
        // Backend không chạy trong test → YARP trả 502, chứng tỏ route khớp và đã thử chuyển tiếp.
        using var client = factory.CreateClient();
        using var response = await client.GetAsync(new Uri("/fhir/Appointment?patient=x", UriKind.Relative));
        Assert.Equal(HttpStatusCode.BadGateway, response.StatusCode);
    }
}
