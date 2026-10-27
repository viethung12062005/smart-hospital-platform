using Npgsql;

namespace Shp.Data.Postgres;

/// <summary>DDL của outbox, inbox, idempotency_key (embedded). Service đưa vào migration của mình; test dùng <see cref="ApplyAsync"/>.</summary>
public static class MessagingSchema
{
    public static string Sql
    {
        get
        {
            using var s = typeof(MessagingSchema).Assembly.GetManifestResourceStream("messaging.sql")
                ?? throw new InvalidOperationException("Thiếu resource messaging.sql");
            using var r = new StreamReader(s);
            return r.ReadToEnd();
        }
    }

    public static async Task ApplyAsync(NpgsqlDataSource dataSource, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(dataSource);
        await using var cmd = dataSource.CreateCommand(Sql);
        await cmd.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }
}
