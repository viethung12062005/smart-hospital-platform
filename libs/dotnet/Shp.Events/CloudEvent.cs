using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace Shp.Events;

/// <summary>Envelope CloudEvents 1.0 của SHP (docs/03-architecture/detailed-design/13-events.md §1; schema: contracts/events/envelope.json).</summary>
public sealed record CloudEvent
{
    [JsonPropertyName("specversion")]
    public string SpecVersion { get; init; } = "1.0";

    [JsonPropertyName("id")]
    public required string Id { get; init; }

    [JsonPropertyName("source")]
    public required string Source { get; init; }

    [JsonPropertyName("type")]
    public required string Type { get; init; }

    [JsonPropertyName("subject")]
    public required string Subject { get; init; }

    [JsonPropertyName("time")]
    public required DateTimeOffset Time { get; init; }

    [JsonPropertyName("datacontenttype")]
    public string DataContentType { get; init; } = "application/json";

    [JsonPropertyName("dataschema")]
    public string? DataSchema { get; init; }

    [JsonPropertyName("traceparent")]
    public string? TraceParent { get; init; }

    [JsonPropertyName("data")]
    public required JsonElement Data { get; init; }
}

public static partial class CloudEvents
{
    /// <summary>Giới hạn kích thước một event (13-events §1).</summary>
    public const int MaxBytes = 64 * 1024;

    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    /// <summary>
    /// Tạo event hợp lệ: id UUIDv7, thời điểm UTC, <c>dataschema</c> suy ra từ <paramref name="type"/>, <c>traceparent</c> từ Activity hiện tại.
    /// <paramref name="subject"/> dạng <c>Appointment/{id}</c> (dùng để giữ thứ tự theo đối tượng trên bus).
    /// </summary>
    public static CloudEvent Create(string service, string type, string subject, object data, TimeProvider? clock = null, Guid? id = null)
    {
        ArgumentException.ThrowIfNullOrEmpty(service);
        var match = TypePattern().Match(type);
        if (!match.Success)
        {
            throw new ArgumentException($"type phải dạng shp.<context>.<event>.v<N>: {type}", nameof(type));
        }

        var evt = new CloudEvent
        {
            Id = (id ?? Guid.CreateVersion7()).ToString(),
            Source = "/services/" + service,
            Type = type,
            Subject = subject,
            Time = (clock ?? TimeProvider.System).GetUtcNow(),
            DataSchema = $"https://shp.example.org/schemas/{match.Groups[1].Value}/{match.Groups[2].Value}/v{match.Groups[3].Value}.json",
            TraceParent = Activity.Current is { IdFormat: ActivityIdFormat.W3C } a ? a.Id : null,
            Data = JsonSerializer.SerializeToElement(data, Json),
        };
        if (JsonSerializer.SerializeToUtf8Bytes(evt, Json).Length > MaxBytes)
        {
            throw new ArgumentException($"Event vượt {MaxBytes} byte", nameof(data));
        }

        return evt;
    }

    public static string ToJson(this CloudEvent evt) => JsonSerializer.Serialize(evt, Json);

    public static CloudEvent Parse(string json) =>
        JsonSerializer.Deserialize<CloudEvent>(json, Json) ?? throw new JsonException("Event rỗng");

    /// <summary>Dạng JsonNode để validate bằng schema.</summary>
    internal static JsonNode ToNode(this CloudEvent evt) => JsonSerializer.SerializeToNode(evt, Json)!;

    [GeneratedRegex(@"^shp\.([a-z]+)\.([a-z-]+)\.v([0-9]+)$")]
    private static partial Regex TypePattern();
}
