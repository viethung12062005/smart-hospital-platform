using System.Reflection;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OpenTelemetry;
using OpenTelemetry.Logs;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

namespace Shp.ServiceDefaults;

/// <summary>
/// Cấu hình chung cho mọi service .NET: OpenTelemetry (trace, metric, log qua OTLP), health check,
/// Problem Details. SA sở hữu file này; dev chỉ gọi <see cref="AddServiceDefaults"/> và <see cref="MapServiceDefaults"/>.
/// </summary>
public static class ServiceDefaultsExtensions
{
    public const string LiveTag = "live";
    public const string ReadyTag = "ready";

    public static WebApplicationBuilder AddServiceDefaults(this WebApplicationBuilder builder, string serviceName)
    {
        ArgumentNullException.ThrowIfNull(builder);

        var version = typeof(ServiceDefaultsExtensions).Assembly
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0.0.0";

        builder.Services.AddProblemDetails();
        builder.Services.AddHealthChecks()
            .AddCheck("self", () => HealthCheckResult.Healthy(), [LiveTag]);

        builder.Logging.AddOpenTelemetry(o =>
        {
            o.IncludeFormattedMessage = true;
            o.IncludeScopes = true;
        });

        var otel = builder.Services.AddOpenTelemetry()
            .ConfigureResource(r => r.AddService(serviceName, serviceVersion: version))
            .WithTracing(t => t.AddAspNetCoreInstrumentation(o => o.Filter = ctx => !IsProbe(ctx.Request.Path)).AddHttpClientInstrumentation())
            .WithMetrics(m => m.AddAspNetCoreInstrumentation().AddHttpClientInstrumentation());

        // Chỉ xuất OTLP khi có endpoint (cluster local/UAT/prod đặt OTEL_EXPORTER_OTLP_ENDPOINT).
        if (!string.IsNullOrWhiteSpace(builder.Configuration["OTEL_EXPORTER_OTLP_ENDPOINT"]))
        {
            otel.UseOtlpExporter();
        }

        builder.Services.AddSingleton(new ServiceInfo(serviceName, version, builder.Configuration["GIT_SHA"] ?? "unknown"));
        return builder;
    }

    public static WebApplication MapServiceDefaults(this WebApplication app)
    {
        ArgumentNullException.ThrowIfNull(app);

        app.UseExceptionHandler();
        app.UseStatusCodePages();

        app.MapHealthChecks("/health/live", new HealthCheckOptions { Predicate = c => c.Tags.Contains(LiveTag) });
        app.MapHealthChecks("/health/ready", new HealthCheckOptions { Predicate = c => c.Tags.Contains(ReadyTag) || c.Tags.Contains(LiveTag) });
        app.MapGet("/info", (ServiceInfo info) => info).ExcludeFromDescription();
        return app;
    }

    private static bool IsProbe(PathString path) =>
        path.StartsWithSegments("/health", StringComparison.Ordinal) || path.StartsWithSegments("/info", StringComparison.Ordinal);
}

/// <summary>Thông tin trả về ở GET /info.</summary>
public sealed record ServiceInfo(string Name, string Version, string GitSha);
