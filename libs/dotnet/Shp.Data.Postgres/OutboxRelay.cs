using System.Diagnostics.Metrics;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Npgsql;
using Shp.Events;

namespace Shp.Data.Postgres;

/// <summary>Đưa một CloudEvent lên bus. Cài đặt: MassTransit (RabbitMQ local, Service Bus cloud) ở bước kế tiếp; test dùng bản giả.</summary>
public interface IEventPublisher
{
    Task PublishAsync(CloudEvent evt, CancellationToken cancellationToken);
}

public sealed class OutboxRelayOptions
{
    public TimeSpan PollInterval { get; set; } = TimeSpan.FromSeconds(1);

    public int BatchSize { get; set; } = 100;

    public TimeSpan MaxBackoff { get; set; } = TimeSpan.FromSeconds(30);
}

/// <summary>
/// Relay outbox → bus. Mỗi lượt: khóa tối đa <c>BatchSize</c> dòng chưa phát bằng <c>FOR UPDATE SKIP LOCKED</c> (nhiều replica chạy song song không trùng),
/// phát lần lượt theo thời gian, đánh dấu <c>published_at</c>. Lỗi bus: ghi <c>attempts</c>/<c>last_error</c>, DỪNG lượt (giữ thứ tự), thử lại sau với backoff.
/// Giao <b>ít nhất một lần</b>: consumer bắt buộc idempotent (inbox).
/// </summary>
public sealed partial class OutboxRelay(
    NpgsqlDataSource dataSource,
    IEventPublisher publisher,
    TimeProvider clock,
    IOptions<OutboxRelayOptions> options,
    ILogger<OutboxRelay> logger) : BackgroundService
{
    private static readonly Meter Meter = new("Shp.Outbox");
    private static readonly Counter<long> Published = Meter.CreateCounter<long>("shp_outbox_published_total");
    private static readonly Counter<long> Failed = Meter.CreateCounter<long>("shp_outbox_publish_failures_total");

    /// <summary>Một lượt phát; trả số event đã phát thành công. Ném lại lỗi của bus sau khi đã ghi nhận.</summary>
    public async Task<int> RelayOnceAsync(CancellationToken cancellationToken)
    {
        await using var connection = await dataSource.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var tx = await connection.BeginTransactionAsync(cancellationToken).ConfigureAwait(false);

        var batch = new List<(Guid Id, string Payload)>();
        await using (var select = new NpgsqlCommand(
            "SELECT id, payload::text FROM outbox WHERE published_at IS NULL ORDER BY occurred_at, id LIMIT @n FOR UPDATE SKIP LOCKED",
            connection, tx))
        {
            select.Parameters.AddWithValue("n", options.Value.BatchSize);
            await using var reader = await select.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
            while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            {
                batch.Add((reader.GetGuid(0), reader.GetString(1)));
            }
        }

        var sent = 0;
        Exception? failure = null;
        foreach (var (id, payload) in batch)
        {
            try
            {
                await publisher.PublishAsync(CloudEvents.Parse(payload), cancellationToken).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                failure = ex;
                Failed.Add(1);
                await using var fail = new NpgsqlCommand(
                    "UPDATE outbox SET attempts = attempts + 1, last_error = @err WHERE id = @id", connection, tx);
                fail.Parameters.AddWithValue("err", Truncate(ex.GetType().Name + ": " + ex.Message, 500)); // không ghi payload (có thể chứa dữ liệu)
                fail.Parameters.AddWithValue("id", id);
                await fail.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
                break;
            }

            await using var mark = new NpgsqlCommand("UPDATE outbox SET published_at = @at WHERE id = @id", connection, tx);
            mark.Parameters.AddWithValue("at", clock.GetUtcNow().UtcDateTime);
            mark.Parameters.AddWithValue("id", id);
            await mark.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            sent++;
            Published.Add(1);
        }

        await tx.CommitAsync(cancellationToken).ConfigureAwait(false); // giữ các dòng đã phát và bộ đếm lỗi
        if (failure is not null)
        {
            throw new OutboxPublishException(sent, failure);
        }

        return sent;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var delay = options.Value.PollInterval;
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var sent = await RelayOnceAsync(stoppingToken).ConfigureAwait(false);
                delay = sent >= options.Value.BatchSize ? TimeSpan.Zero : options.Value.PollInterval; // còn việc thì phát tiếp ngay
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                LogRelayFailure(logger, ex);
                delay = TimeSpan.FromTicks(Math.Min(options.Value.MaxBackoff.Ticks, Math.Max(delay.Ticks, options.Value.PollInterval.Ticks) * 2));
            }

            try
            {
                await Task.Delay(delay, clock, stoppingToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private static string Truncate(string s, int max) => s.Length <= max ? s : s[..max];

    [LoggerMessage(Level = LogLevel.Warning, Message = "Outbox relay failed; will retry with backoff")]
    private static partial void LogRelayFailure(ILogger logger, Exception exception);
}

/// <summary>Bus từ chối một event; các event trước đó trong lượt vẫn được ghi nhận đã phát.</summary>
public sealed class OutboxPublishException(int publishedBeforeFailure, Exception inner)
    : Exception("Không phát được event từ outbox", inner)
{
    public int PublishedBeforeFailure { get; } = publishedBeforeFailure;
}
