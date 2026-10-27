using Npgsql;
using Shp.Events;

namespace Shp.Data.Postgres;

/// <summary>Inbox chống xử lý trùng: mỗi (consumer, messageId) chỉ được xử lý một lần, kể cả khi bus giao nhiều lần hoặc nhiều replica cùng nhận.</summary>
public static class PostgresInbox
{
    /// <summary>true nếu đây là lần đầu (đã ghi dấu trong transaction); false nếu đã xử lý trước đó. Dấu bị hủy nếu transaction rollback.</summary>
    public static async Task<bool> TryBeginAsync(NpgsqlTransaction transaction, string consumer, Guid messageId, TimeProvider clock, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(transaction);
        ArgumentNullException.ThrowIfNull(clock);
        await using var cmd = new NpgsqlCommand(
            "INSERT INTO inbox (consumer, message_id, processed_at) VALUES (@c, @m, @t) ON CONFLICT DO NOTHING",
            transaction.Connection, transaction);
        cmd.Parameters.AddWithValue("c", consumer);
        cmd.Parameters.AddWithValue("m", messageId);
        cmd.Parameters.AddWithValue("t", clock.GetUtcNow().UtcDateTime);
        return await cmd.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false) == 1;
    }

    /// <summary>
    /// Xử lý event đúng một lần: mở transaction → ghi dấu inbox → chạy <paramref name="handler"/> (dùng cùng transaction cho thay đổi dữ liệu và outbox) → commit.
    /// Handler lỗi → rollback TOÀN BỘ (cả dấu inbox) nên lần giao lại sẽ xử lý lại. Trả false nếu event đã xử lý (bỏ qua).
    /// </summary>
    public static async Task<bool> HandleOnceAsync(
        NpgsqlDataSource dataSource,
        string consumer,
        CloudEvent evt,
        Func<NpgsqlTransaction, Task> handler,
        TimeProvider? clock = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(dataSource);
        ArgumentNullException.ThrowIfNull(evt);
        ArgumentNullException.ThrowIfNull(handler);
        await using var connection = await dataSource.OpenConnectionAsync(cancellationToken).ConfigureAwait(false);
        await using var tx = await connection.BeginTransactionAsync(cancellationToken).ConfigureAwait(false);
        if (!await TryBeginAsync(tx, consumer, Guid.Parse(evt.Id), clock ?? TimeProvider.System, cancellationToken).ConfigureAwait(false))
        {
            await tx.RollbackAsync(cancellationToken).ConfigureAwait(false);
            return false;
        }

        await handler(tx).ConfigureAwait(false);
        await tx.CommitAsync(cancellationToken).ConfigureAwait(false);
        return true;
    }
}
