using Npgsql;
using NpgsqlTypes;
using Shp.Events;

namespace Shp.Data.Postgres;

/// <summary>Ghi event vào bảng <c>outbox</c> TRONG CÙNG transaction với thay đổi dữ liệu (13-events §1): hoặc cả hai thành công, hoặc cả hai không.</summary>
public static class PostgresOutbox
{
    /// <param name="validate">true: kiểm event theo schema trước khi ghi (nên bật trong test/dev; production đã có test hợp đồng).</param>
    public static async Task AddAsync(NpgsqlTransaction transaction, CloudEvent evt, bool validate = false, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(transaction);
        ArgumentNullException.ThrowIfNull(evt);
        if (validate && EventSchemas.Validate(evt) is { Count: > 0 } errors)
        {
            throw new ArgumentException("Event không khớp schema: " + string.Join("; ", errors), nameof(evt));
        }

        await using var cmd = new NpgsqlCommand(
            "INSERT INTO outbox (id, event_type, subject, payload, occurred_at) VALUES (@id, @type, @subject, @payload, @at)",
            transaction.Connection, transaction);
        cmd.Parameters.AddWithValue("id", Guid.Parse(evt.Id));
        cmd.Parameters.AddWithValue("type", evt.Type);
        cmd.Parameters.AddWithValue("subject", evt.Subject);
        cmd.Parameters.Add(new NpgsqlParameter("payload", NpgsqlDbType.Jsonb) { Value = evt.ToJson() });
        cmd.Parameters.AddWithValue("at", evt.Time.UtcDateTime);
        await cmd.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }
}
