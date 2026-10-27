using Npgsql;
using Shp.Events;
using Xunit;

namespace Shp.Data.Postgres.Tests;

[Collection("postgres")]
public sealed class InboxTests(PostgresFixture db) : IAsyncLifetime
{
    public Task InitializeAsync() => db.Reset();

    public Task DisposeAsync() => Task.CompletedTask;

    private static CloudEvent Evt() => CloudEvents.Create("clinical", "shp.clinical.encounter-finished.v1", "Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e05",
        new { encounterId = Guid.CreateVersion7(), appointmentId = Guid.CreateVersion7(), patientId = Guid.CreateVersion7(), practitionerId = Guid.CreateVersion7(), finishedAt = "2026-12-03T02:00:00Z", version = 3 });

    private static async Task Insert(NpgsqlTransaction tx, string name)
    {
        await using var cmd = new NpgsqlCommand("INSERT INTO thing VALUES (@i, @n)", tx.Connection, tx);
        cmd.Parameters.AddWithValue("i", Guid.NewGuid());
        cmd.Parameters.AddWithValue("n", name);
        await cmd.ExecuteNonQueryAsync();
    }

    [Fact]
    public async Task Same_event_delivered_twice_is_handled_once()
    {
        var evt = Evt();
        var runs = 0;
        Task Handler(NpgsqlTransaction tx) { runs++; return Insert(tx, "x"); }

        Assert.True(await PostgresInbox.HandleOnceAsync(db.DataSource, "scheduling", evt, Handler));
        Assert.False(await PostgresInbox.HandleOnceAsync(db.DataSource, "scheduling", evt, Handler));
        Assert.Equal(1, runs);
        Assert.Equal(1L, await db.Scalar<long>("SELECT count(*) FROM thing"));
    }

    [Fact]
    public async Task Different_consumers_each_handle_the_same_event()
    {
        var evt = Evt();
        Assert.True(await PostgresInbox.HandleOnceAsync(db.DataSource, "scheduling", evt, tx => Insert(tx, "a")));
        Assert.True(await PostgresInbox.HandleOnceAsync(db.DataSource, "notification", evt, tx => Insert(tx, "b")));
        Assert.Equal(2L, await db.Scalar<long>("SELECT count(*) FROM inbox"));
    }

    [Fact]
    public async Task Failing_handler_rolls_everything_back_so_redelivery_is_processed()
    {
        var evt = Evt();
        await Assert.ThrowsAsync<InvalidOperationException>(() => PostgresInbox.HandleOnceAsync(db.DataSource, "c", evt, async tx =>
        {
            await Insert(tx, "half-done");
            throw new InvalidOperationException("boom");
        }));
        Assert.Equal(0L, await db.Scalar<long>("SELECT count(*) FROM thing"));
        Assert.Equal(0L, await db.Scalar<long>("SELECT count(*) FROM inbox"));

        Assert.True(await PostgresInbox.HandleOnceAsync(db.DataSource, "c", evt, tx => Insert(tx, "ok")));
        Assert.Equal(1L, await db.Scalar<long>("SELECT count(*) FROM thing"));
    }

    [Fact]
    public async Task Concurrent_deliveries_run_the_handler_exactly_once()
    {
        var evt = Evt();
        var runs = 0;
        var results = await Task.WhenAll(Enumerable.Range(0, 20).Select(_ => PostgresInbox.HandleOnceAsync(db.DataSource, "scheduling", evt, async tx =>
        {
            Interlocked.Increment(ref runs);
            await Insert(tx, "x");
        })));
        Assert.Equal(1, results.Count(r => r));
        Assert.Equal(1, runs);
        Assert.Equal(1L, await db.Scalar<long>("SELECT count(*) FROM thing"));
    }

    [Fact]
    public async Task Handler_can_write_data_and_outbox_in_one_transaction()
    {
        var evt = Evt();
        await PostgresInbox.HandleOnceAsync(db.DataSource, "scheduling", evt, async tx =>
        {
            await Insert(tx, "fulfilled");
            await PostgresOutbox.AddAsync(tx, CloudEvents.Create("scheduling", "shp.scheduling.appointment-status-changed.v1", "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e00",
                new { appointmentId = Guid.CreateVersion7(), version = 4, patientId = Guid.CreateVersion7(), previousStatus = "arrived", newStatus = "fulfilled", changedAt = "2026-12-03T02:00:00Z" }), validate: true);
        });
        Assert.Equal(1L, await db.Scalar<long>("SELECT count(*) FROM outbox"));
        Assert.Equal(1L, await db.Scalar<long>("SELECT count(*) FROM thing"));
    }
}
