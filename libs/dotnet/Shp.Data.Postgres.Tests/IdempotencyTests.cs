using System.Text;
using Npgsql;
using Shp.Web.Errors;
using Xunit;

namespace Shp.Data.Postgres.Tests;

[Collection("postgres")]
public sealed class IdempotencyTests(PostgresFixture db) : IAsyncLifetime
{
    private static readonly MutableClock Clock = new(new DateTimeOffset(2026, 11, 20, 2, 0, 0, TimeSpan.Zero));
    private const string Key = "7b0e6c52-1111-4f3a-9d11-aaaaaaaaaaaa";

    public Task InitializeAsync()
    {
        Clock.Now = new DateTimeOffset(2026, 11, 20, 2, 0, 0, TimeSpan.Zero);
        return db.Reset();
    }

    public Task DisposeAsync() => Task.CompletedTask;

    private static byte[] Hash(string json) => PostgresIdempotency.HashBody(Encoding.UTF8.GetBytes(json));

    private async Task<IdempotencyClaim> Claim(string subject = "sub-1", string key = Key, string op = "Appointment/$book", string body = """{"slotId":"s1"}""", bool commit = true)
    {
        await using var conn = await db.DataSource.OpenConnectionAsync();
        await using var tx = await conn.BeginTransactionAsync();
        var claim = await PostgresIdempotency.ClaimAsync(tx, subject, key, op, Hash(body), Clock);
        if (commit)
        {
            await tx.CommitAsync();
        }
        else
        {
            await tx.RollbackAsync();
        }

        return claim;
    }

    [Fact]
    public async Task First_request_claims_and_same_request_replays_the_same_resource()
    {
        var first = await Claim();
        Assert.False(first.IsReplay);
        var again = await Claim();
        Assert.True(again.IsReplay);
        Assert.Equal(first.ResourceId, again.ResourceId);
    }

    [Fact]
    public async Task Same_key_with_a_different_body_or_operation_is_422()
    {
        await Claim();
        var body = await Assert.ThrowsAsync<ShpException>(() => Claim(body: """{"slotId":"OTHER"}"""));
        Assert.Equal("idempotency-key-reused", body.Info.Code);
        Assert.Equal(422, body.Info.Status);
        await Assert.ThrowsAsync<ShpException>(() => Claim(op: "Appointment/$cancel"));
    }

    [Fact]
    public async Task Reordered_properties_and_whitespace_still_count_as_the_same_request()
    {
        await Claim(body: """{"a":1,"b":{"x":1,"y":2}}""");
        var replay = await Claim(body: "{ \"b\": { \"y\": 2, \"x\": 1 },\n \"a\": 1 }");
        Assert.True(replay.IsReplay);
    }

    [Fact]
    public async Task Keys_are_scoped_per_caller()
    {
        var a = await Claim(subject: "alice");
        var b = await Claim(subject: "bob");
        Assert.False(b.IsReplay);
        Assert.NotEqual(a.ResourceId, b.ResourceId);
    }

    [Fact]
    public async Task Parallel_requests_with_one_key_produce_exactly_one_new_claim()
    {
        var claims = await Task.WhenAll(Enumerable.Range(0, 20).Select(_ => Claim()));
        Assert.Equal(1, claims.Count(c => !c.IsReplay));
        Assert.Single(claims.Select(c => c.ResourceId).Distinct());
        Assert.Equal(1L, await db.Scalar<long>("SELECT count(*) FROM idempotency_key"));
    }

    [Fact]
    public async Task Rolled_back_claim_can_be_claimed_again()
    {
        var first = await Claim(commit: false);
        Assert.False(first.IsReplay);
        var second = await Claim();
        Assert.False(second.IsReplay);
    }

    [Fact]
    public async Task Key_expires_after_24_hours()
    {
        var first = await Claim();
        Clock.Now += TimeSpan.FromHours(25);
        var later = await Claim();
        Assert.False(later.IsReplay);
        Assert.NotEqual(first.ResourceId, later.ResourceId);
    }

    [Theory]
    [InlineData(null, "idempotency-key-required")]
    [InlineData("", "idempotency-key-required")]
    [InlineData("short", "validation-failed")]
    [InlineData("has space in the key", "validation-failed")]
    public void Bad_header_is_rejected(string? header, string code) =>
        Assert.Equal(code, Assert.Throws<ShpException>(() => PostgresIdempotency.RequireKey(header)).Info.Code);

    [Fact]
    public void Valid_header_is_accepted() => Assert.Equal(Key, PostgresIdempotency.RequireKey(Key));

    [Fact]
    public void Body_hash_is_stable_for_non_json_and_differs_for_different_values()
    {
        Assert.Equal(PostgresIdempotency.HashBody("not json"u8), PostgresIdempotency.HashBody("not json"u8));
        Assert.NotEqual(Hash("""{"a":1}"""), Hash("""{"a":2}"""));
    }
}
