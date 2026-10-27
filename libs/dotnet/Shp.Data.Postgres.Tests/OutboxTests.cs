using System.Collections.Concurrent;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Npgsql;
using Shp.Events;
using Xunit;

namespace Shp.Data.Postgres.Tests;

[Collection("postgres")]
public sealed class OutboxTests(PostgresFixture db) : IAsyncLifetime
{
    private static readonly MutableClock Clock = new(new DateTimeOffset(2026, 11, 20, 2, 0, 0, TimeSpan.Zero));

    public Task InitializeAsync() => db.Reset();

    public Task DisposeAsync() => Task.CompletedTask;

    private static CloudEvent Evt(int n, DateTimeOffset? at = null) => CloudEvents.Create(
        "scheduling", "shp.scheduling.appointment-checked-in.v1", $"Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e{n:00}",
        new { appointmentId = Guid.CreateVersion7(), version = n, patientId = Guid.CreateVersion7(), practitionerId = Guid.CreateVersion7(), specialty = "cardiology", checkedInAt = "2026-12-03T01:15:00Z" },
        new FixedAt(at ?? Clock.GetUtcNow().AddSeconds(n)));

    private OutboxRelay Relay(IEventPublisher publisher, int batch = 100) => new(
        db.DataSource, publisher, Clock, Options.Create(new OutboxRelayOptions { BatchSize = batch }), NullLogger<OutboxRelay>.Instance);

    private async Task AddAsync(params CloudEvent[] events)
    {
        await using var conn = await db.DataSource.OpenConnectionAsync();
        await using var tx = await conn.BeginTransactionAsync();
        foreach (var e in events)
        {
            await PostgresOutbox.AddAsync(tx, e);
        }

        await tx.CommitAsync();
    }

    [Fact]
    public async Task Data_change_and_event_commit_or_roll_back_together()
    {
        await using (var conn = await db.DataSource.OpenConnectionAsync())
        await using (var tx = await conn.BeginTransactionAsync())
        {
            await using var cmd = new NpgsqlCommand("INSERT INTO thing VALUES (@i, 'a')", conn, tx);
            cmd.Parameters.AddWithValue("i", Guid.NewGuid());
            await cmd.ExecuteNonQueryAsync();
            await PostgresOutbox.AddAsync(tx, Evt(1));
            await tx.RollbackAsync();
        }

        Assert.Equal(0L, await db.Scalar<long>("SELECT count(*) FROM thing"));
        Assert.Equal(0L, await db.Scalar<long>("SELECT count(*) FROM outbox"));

        await AddAsync(Evt(1));
        Assert.Equal(1L, await db.Scalar<long>("SELECT count(*) FROM outbox WHERE published_at IS NULL"));
    }

    [Fact]
    public async Task Relay_publishes_in_time_order_and_marks_published()
    {
        var pub = new RecordingPublisher();
        await AddAsync(Evt(3), Evt(1), Evt(2));

        Assert.Equal(3, await Relay(pub).RelayOnceAsync(default));
        Assert.Equal([1, 2, 3], pub.Published.Select(e => e.Data.GetProperty("version").GetInt32()));
        Assert.Equal(0, await Relay(pub).RelayOnceAsync(default));
        Assert.Equal(3L, await db.Scalar<long>("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL"));
    }

    [Fact]
    public async Task Published_event_is_the_same_cloud_event_that_was_stored()
    {
        var pub = new RecordingPublisher();
        var original = Evt(1);
        await AddAsync(original);
        await Relay(pub).RelayOnceAsync(default);
        var got = Assert.Single(pub.Published);
        Assert.Equal(original.Id, got.Id);
        Assert.Equal(original.Type, got.Type);
        Assert.Equal(original.Subject, got.Subject);
        Assert.Empty(EventSchemas.Validate(got));
    }

    [Fact]
    public async Task Bus_outage_loses_nothing_and_recovers_in_order()
    {
        var pub = new RecordingPublisher { FailOnVersion = 2 };
        await AddAsync(Evt(1), Evt(2), Evt(3));

        var ex = await Assert.ThrowsAsync<OutboxPublishException>(() => Relay(pub).RelayOnceAsync(default));
        Assert.Equal(1, ex.PublishedBeforeFailure);
        Assert.Equal([1], pub.Published.Select(e => e.Data.GetProperty("version").GetInt32()));
        Assert.Equal(1, await db.Scalar<short>("SELECT attempts FROM outbox WHERE subject LIKE '%e02'"));
        Assert.Equal(0, await db.Scalar<short>("SELECT attempts FROM outbox WHERE subject LIKE '%e03'")); // không bị chạm tới

        pub.FailOnVersion = null; // bus sống lại
        Assert.Equal(2, await Relay(pub).RelayOnceAsync(default));
        Assert.Equal([1, 2, 3], pub.Published.Select(e => e.Data.GetProperty("version").GetInt32()));
    }

    [Fact]
    public async Task Last_error_never_contains_the_payload()
    {
        var pub = new RecordingPublisher { FailOnVersion = 1, FailMessage = "broker refused" };
        await AddAsync(Evt(1));
        await Assert.ThrowsAsync<OutboxPublishException>(() => Relay(pub).RelayOnceAsync(default));
        var err = await db.Scalar<string>("SELECT last_error FROM outbox");
        Assert.Contains("broker refused", err, StringComparison.Ordinal);
        Assert.DoesNotContain("patientId", err, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Several_replicas_never_publish_an_event_twice()
    {
        var pub = new RecordingPublisher { Delay = TimeSpan.FromMilliseconds(3) };
        await AddAsync([.. Enumerable.Range(1, 60).Select(n => Evt(n))]);

        var relays = Enumerable.Range(0, 4).Select(_ => Relay(pub, batch: 10)).ToList();
        var rounds = await Task.WhenAll(relays.Select(async r =>
        {
            var total = 0;
            int sent;
            do
            {
                sent = await r.RelayOnceAsync(default);
                total += sent;
            }
            while (sent > 0);
            return total;
        }));

        Assert.Equal(60, rounds.Sum());
        Assert.Equal(60, pub.Published.Count);
        Assert.Equal(60, pub.Published.Select(e => e.Id).Distinct().Count());
        Assert.Equal(0L, await db.Scalar<long>("SELECT count(*) FROM outbox WHERE published_at IS NULL"));
    }

    [Fact]
    public async Task Validate_flag_rejects_an_event_that_breaks_its_schema()
    {
        var bad = CloudEvents.Create("scheduling", "shp.scheduling.appointment-booked.v1", "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70", new { appointmentId = "x" });
        await using var conn = await db.DataSource.OpenConnectionAsync();
        await using var tx = await conn.BeginTransactionAsync();
        await Assert.ThrowsAsync<ArgumentException>(() => PostgresOutbox.AddAsync(tx, bad, validate: true));
    }

    private sealed class FixedAt(DateTimeOffset t) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => t;
    }

    private sealed class RecordingPublisher : IEventPublisher
    {
        private readonly ConcurrentQueue<CloudEvent> _published = new();

        public int? FailOnVersion { get; set; }

        public string FailMessage { get; set; } = "bus down";

        public TimeSpan Delay { get; set; }

        public IReadOnlyCollection<CloudEvent> Published => [.. _published];

        public async Task PublishAsync(CloudEvent evt, CancellationToken cancellationToken)
        {
            if (FailOnVersion is { } v && evt.Data.GetProperty("version").GetInt32() == v)
            {
                throw new InvalidOperationException(FailMessage);
            }

            if (Delay > TimeSpan.Zero)
            {
                await Task.Delay(Delay, cancellationToken);
            }

            _published.Enqueue(evt);
        }
    }
}
