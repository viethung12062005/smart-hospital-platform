using System.Security.Cryptography;
using System.Text.Json;
using Npgsql;
using Shp.Web.Errors;

namespace Shp.Data.Postgres;

/// <summary>Kết quả claim. <see cref="IsReplay"/>=true: yêu cầu này đã được xử lý trước đó với <see cref="ResourceId"/> — trả lại kết quả cũ, KHÔNG xử lý lại.</summary>
public sealed record IdempotencyClaim(Guid ResourceId, bool IsReplay);

/// <summary>Idempotency-Key cho PostgreSQL (07-api-conventions §3, 02-db-scheduling §3.1). Claim nằm trong CÙNG transaction với thay đổi dữ liệu.</summary>
public static class PostgresIdempotency
{
    public static readonly TimeSpan Retention = TimeSpan.FromHours(24);

    /// <summary>Kiểm header: thiếu → 428 <c>idempotency-key-required</c>; sai độ dài/ký tự → 400 <c>validation-failed</c> (8–128 ký tự in được, không khoảng trắng).</summary>
    public static string RequireKey(string? header)
    {
        if (string.IsNullOrWhiteSpace(header))
        {
            throw new ShpException("idempotency-key-required");
        }

        if (header.Length is < 8 or > 128 || header.Any(c => c <= ' ' || c > '~'))
        {
            throw new ShpException("validation-failed", "Idempotency-Key phải dài 8–128 ký tự, không chứa khoảng trắng.");
        }

        return header;
    }

    /// <summary>SHA-256 của body đã chuẩn hóa (JSON: sắp xếp tên thuộc tính, bỏ khoảng trắng thừa; không phải JSON: băm nguyên byte).</summary>
    public static byte[] HashBody(ReadOnlySpan<byte> body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body.ToArray());
            using var buffer = new MemoryStream();
            using (var writer = new Utf8JsonWriter(buffer))
            {
                WriteCanonical(doc.RootElement, writer);
            }

            return SHA256.HashData(buffer.ToArray());
        }
        catch (JsonException)
        {
            return SHA256.HashData(body);
        }
    }

    /// <summary>
    /// Giành quyền xử lý (subject, key). Lần đầu: ghi dòng và cấp sẵn <c>ResourceId</c>. Cùng key + cùng thao tác + cùng body: replay.
    /// Cùng key nhưng khác thao tác hoặc body: 422 <c>idempotency-key-reused</c>. Hai request song song cùng key: request sau chờ request trước kết thúc rồi thành replay (hoặc giành lại nếu request trước rollback).
    /// </summary>
    public static async Task<IdempotencyClaim> ClaimAsync(
        NpgsqlTransaction transaction,
        string subject,
        string key,
        string operation,
        byte[] requestHash,
        TimeProvider clock,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(transaction);
        ArgumentNullException.ThrowIfNull(clock);
        var now = clock.GetUtcNow().UtcDateTime;
        var connection = transaction.Connection!;

        // Dòng quá 24 giờ coi như hết hạn (job dọn dẹp chỉ xóa vật lý).
        await using (var expire = new NpgsqlCommand("DELETE FROM idempotency_key WHERE subject = @s AND key = @k AND created_at < @cutoff", connection, transaction))
        {
            expire.Parameters.AddWithValue("s", subject);
            expire.Parameters.AddWithValue("k", key);
            expire.Parameters.AddWithValue("cutoff", now - Retention);
            await expire.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }

        var resourceId = Guid.CreateVersion7();
        await using (var insert = new NpgsqlCommand(
            "INSERT INTO idempotency_key (subject, key, operation, request_hash, resource_id, created_at) VALUES (@s, @k, @o, @h, @r, @t) ON CONFLICT (subject, key) DO NOTHING",
            connection, transaction))
        {
            insert.Parameters.AddWithValue("s", subject);
            insert.Parameters.AddWithValue("k", key);
            insert.Parameters.AddWithValue("o", operation);
            insert.Parameters.AddWithValue("h", requestHash);
            insert.Parameters.AddWithValue("r", resourceId);
            insert.Parameters.AddWithValue("t", now);
            if (await insert.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false) == 1)
            {
                return new IdempotencyClaim(resourceId, IsReplay: false);
            }
        }

        await using var select = new NpgsqlCommand("SELECT operation, request_hash, resource_id FROM idempotency_key WHERE subject = @s AND key = @k", connection, transaction);
        select.Parameters.AddWithValue("s", subject);
        select.Parameters.AddWithValue("k", key);
        await using var reader = await select.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        if (!await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            throw new InvalidOperationException("Dòng idempotency biến mất giữa hai câu lệnh"); // không xảy ra: cùng transaction
        }

        var sameOperation = reader.GetString(0) == operation;
        var sameBody = CryptographicOperations.FixedTimeEquals((byte[])reader[1], requestHash);
        if (!sameOperation || !sameBody)
        {
            throw new ShpException("idempotency-key-reused");
        }

        return new IdempotencyClaim(reader.GetGuid(2), IsReplay: true);
    }

    private static void WriteCanonical(JsonElement e, Utf8JsonWriter w)
    {
        switch (e.ValueKind)
        {
            case JsonValueKind.Object:
                w.WriteStartObject();
                foreach (var p in e.EnumerateObject().OrderBy(p => p.Name, StringComparer.Ordinal))
                {
                    w.WritePropertyName(p.Name);
                    WriteCanonical(p.Value, w);
                }

                w.WriteEndObject();
                break;
            case JsonValueKind.Array:
                w.WriteStartArray();
                foreach (var item in e.EnumerateArray())
                {
                    WriteCanonical(item, w);
                }

                w.WriteEndArray();
                break;
            default:
                e.WriteTo(w);
                break;
        }
    }
}
