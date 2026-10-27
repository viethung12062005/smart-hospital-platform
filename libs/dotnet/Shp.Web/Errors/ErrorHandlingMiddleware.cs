using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace Shp.Web.Errors;

internal sealed partial class ErrorHandlingMiddleware(RequestDelegate next, ILogger<ErrorHandlingMiddleware> logger)
{
    public async Task InvokeAsync(HttpContext context)
    {
        try
        {
            await next(context).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested)
        {
            // Client ngắt kết nối: không phải lỗi hệ thống.
        }
        catch (ShpException ex) when (!context.Response.HasStarted)
        {
            await ErrorWriter.WriteAsync(context, ex.Info, ex.Detail, ex.RetryAfter).ConfigureAwait(false);
        }
        catch (Exception ex) when (!context.Response.HasStarted)
        {
            // Không đưa nội dung exception cho client (có thể chứa PII/stack trace).
            LogUnhandled(logger, ex);
            await ErrorWriter.WriteAsync(context, ErrorCatalog.Get("internal-error")).ConfigureAwait(false);
        }
    }

    [LoggerMessage(Level = LogLevel.Error, Message = "Unhandled exception")]
    private static partial void LogUnhandled(ILogger logger, Exception exception);
}

public static class ErrorHandlingExtensions
{
    /// <summary>Đặt TRƯỚC authentication/authorization và trước <c>MapServiceDefaults</c>.</summary>
    public static IApplicationBuilder UseShpErrors(this IApplicationBuilder app) => app.UseMiddleware<ErrorHandlingMiddleware>();
}
