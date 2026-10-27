using System.Diagnostics;
using System.Globalization;
using System.Text.Json;
using Microsoft.AspNetCore.Http;

namespace Shp.Web.Errors;

/// <summary>Ghi lỗi đúng định dạng: <c>/fhir/*</c> → OperationOutcome; còn lại → Problem Details (RFC 9457) có trường <c>code</c>.</summary>
public static class ErrorWriter
{
    public const string FhirErrorSystem = "https://shp.example.org/fhir/CodeSystem/error";

    public static async Task WriteAsync(HttpContext context, ErrorInfo info, string? detail = null, TimeSpan? retryAfter = null)
    {
        ArgumentNullException.ThrowIfNull(context);
        var traceId = Activity.Current?.TraceId.ToString() ?? context.TraceIdentifier;
        var message = detail ?? (WantsEnglish(context) ? info.MessageEn : info.MessageVi);

        context.Response.StatusCode = info.Status;
        context.Response.Headers["X-Request-Id"] = traceId;
        if (retryAfter is not null || info.Status is 429 or 503)
        {
            context.Response.Headers.RetryAfter = ((int)(retryAfter ?? TimeSpan.FromSeconds(5)).TotalSeconds).ToString(CultureInfo.InvariantCulture);
        }

        if (context.Request.Path.StartsWithSegments("/fhir", StringComparison.OrdinalIgnoreCase))
        {
            context.Response.ContentType = "application/fhir+json";
            await JsonSerializer.SerializeAsync(context.Response.Body, new
            {
                resourceType = "OperationOutcome",
                issue = new[]
                {
                    new
                    {
                        severity = "error",
                        code = info.FhirIssueType,
                        details = new { coding = new[] { new { system = FhirErrorSystem, code = info.Code } } },
                        diagnostics = message,
                    },
                },
            }, JsonSerializerOptions.Default, context.RequestAborted).ConfigureAwait(false);
            return;
        }

        context.Response.ContentType = "application/problem+json";
        await JsonSerializer.SerializeAsync(context.Response.Body, new
        {
            type = "https://shp.example.org/errors/" + info.Code,
            title = info.Code,
            status = info.Status,
            code = info.Code,
            detail = message,
            traceId,
        }, JsonSerializerOptions.Default, context.RequestAborted).ConfigureAwait(false);
    }

    private static bool WantsEnglish(HttpContext context) =>
        context.Request.Headers.AcceptLanguage.ToString().StartsWith("en", StringComparison.OrdinalIgnoreCase);
}
