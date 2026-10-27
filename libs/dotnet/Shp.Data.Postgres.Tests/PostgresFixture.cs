using Npgsql;
using Testcontainers.PostgreSql;
using Xunit;

namespace Shp.Data.Postgres.Tests;

/// <summary>PostgreSQL 16 thật trong Docker (Testcontainers). Đổi image bằng biến SHP_TEST_POSTGRES_IMAGE (ví dụ mirror khi Docker Hub giới hạn lượt kéo).</summary>
public sealed class PostgresFixture : IAsyncLifetime
{
    private readonly PostgreSqlContainer _container = new PostgreSqlBuilder(
        Environment.GetEnvironmentVariable("SHP_TEST_POSTGRES_IMAGE") ?? "postgres:16-alpine").Build();

    public NpgsqlDataSource DataSource { get; private set; } = null!;

    public async Task InitializeAsync()
    {
        await _container.StartAsync();
        DataSource = NpgsqlDataSource.Create(_container.GetConnectionString());
        await MessagingSchema.ApplyAsync(DataSource);
        await using var cmd = DataSource.CreateCommand("CREATE TABLE IF NOT EXISTS thing (id uuid PRIMARY KEY, name text NOT NULL)");
        await cmd.ExecuteNonQueryAsync();
    }

    public async Task Reset()
    {
        await using var cmd = DataSource.CreateCommand("TRUNCATE outbox, inbox, idempotency_key, thing");
        await cmd.ExecuteNonQueryAsync();
    }

    public async Task<T> Scalar<T>(string sql)
    {
        await using var cmd = DataSource.CreateCommand(sql);
        return (T)(await cmd.ExecuteScalarAsync())!;
    }

    public async Task DisposeAsync()
    {
        await DataSource.DisposeAsync();
        await _container.DisposeAsync();
    }
}

[CollectionDefinition("postgres")]
public sealed class PostgresTestGroup : ICollectionFixture<PostgresFixture>;

internal sealed class MutableClock(DateTimeOffset start) : TimeProvider
{
    public DateTimeOffset Now { get; set; } = start;

    public override DateTimeOffset GetUtcNow() => Now;
}
