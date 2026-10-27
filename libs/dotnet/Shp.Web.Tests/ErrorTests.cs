using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;
using Shp.Web.Errors;
using Xunit;

namespace Shp.Web.Tests;

public sealed partial class ErrorTests : IAsyncLifetime
{
    private TestApp _app = null!;

    public async Task InitializeAsync() => _app = await TestApp.StartAsync();

    public async Task DisposeAsync() => await _app.DisposeAsync();

    [Fact]
    public async Task Api_error_is_problem_json_with_code_and_vietnamese_message()
    {
        var r = await _app.GetAsync("/api/v1/slot");
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("application/problem+json", r.Content.Headers.ContentType?.MediaType);
        var b = JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement;
        Assert.Equal("slot-unavailable", b.GetProperty("code").GetString());
        Assert.Contains("khung giờ", b.GetProperty("detail").GetString(), StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Fhir_error_is_operation_outcome()
    {
        var r = await _app.GetAsync("/fhir/Patient/x", language: "en");
        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
        Assert.Equal("application/fhir+json", r.Content.Headers.ContentType?.MediaType);
        var issue = JsonDocument.Parse(await r.Content.ReadAsStringAsync()).RootElement.GetProperty("issue")[0];
        Assert.Equal("not-found", issue.GetProperty("code").GetString());
        Assert.Equal("not-found", issue.GetProperty("details").GetProperty("coding")[0].GetProperty("code").GetString());
        Assert.Equal("Not found.", issue.GetProperty("diagnostics").GetString());
    }

    [Fact]
    public async Task Rate_limited_carries_retry_after()
    {
        var r = await _app.GetAsync("/api/v1/limited");
        Assert.Equal((HttpStatusCode)429, r.StatusCode);
        Assert.Equal("30", r.Headers.RetryAfter?.Delta?.TotalSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture));
    }

    [Fact]
    public async Task Unhandled_exception_is_500_without_leaking_details()
    {
        var r = await _app.GetAsync("/api/v1/boom");
        Assert.Equal(HttpStatusCode.InternalServerError, r.StatusCode);
        var text = await r.Content.ReadAsStringAsync();
        Assert.Contains("internal-error", text, StringComparison.Ordinal);
        Assert.DoesNotContain("Nguyen", text, StringComparison.Ordinal);
        Assert.DoesNotContain("0901234567", text, StringComparison.Ordinal);
    }

    [Fact]
    public void Unknown_code_cannot_be_thrown()
    {
        Assert.Throws<ArgumentException>(() => new ShpException("made-up-code"));
    }

    [Fact]
    public void Catalog_matches_the_error_table_in_the_design_document()
    {
        var dir = AppContext.BaseDirectory;
        while (dir is not null && !File.Exists(Path.Combine(dir, "docs", "03-architecture", "detailed-design", "07-api-conventions.md")))
        {
            dir = Path.GetDirectoryName(dir);
        }

        Assert.NotNull(dir);
        var text = File.ReadAllText(Path.Combine(dir, "docs", "03-architecture", "detailed-design", "07-api-conventions.md"));
        var section = text[text.IndexOf("## 5. Lỗi", StringComparison.Ordinal)..text.IndexOf("## 6.", StringComparison.Ordinal)];
        var documented = TableRow().Matches(section).Select(m => (Status: int.Parse(m.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture), Codes: m.Groups[2].Value))
            .SelectMany(r => CodeToken().Matches(r.Codes).Select(c => (r.Status, Code: c.Groups[1].Value))).ToList();

        Assert.NotEmpty(documented);
        foreach (var (status, code) in documented)
        {
            Assert.Equal(status, ErrorCatalog.Get(code).Status);
        }

        var inCatalog = ErrorCatalog.All.Select(e => e.Code).Where(c => c != "internal-error").Order().ToList();
        Assert.Equal(documented.Select(d => d.Code).Order().ToList(), inCatalog);
    }

    [GeneratedRegex(@"^\| (\d{3}) \|[^|]*\|(.*)\|\s*$", RegexOptions.Multiline)]
    private static partial Regex TableRow();

    [GeneratedRegex("`([a-z-]+)`")]
    private static partial Regex CodeToken();
}
