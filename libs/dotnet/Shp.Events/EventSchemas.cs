using System.Reflection;
using System.Text.Json.Nodes;
using Json.Schema;

namespace Shp.Events;

/// <summary>Kiểm event theo schema trong contracts/events (nhúng vào thư viện). Dùng trong test hợp đồng của producer và consumer.</summary>
public static class EventSchemas
{
    private static readonly Lazy<(JsonSchema Envelope, Dictionary<string, JsonSchema> Data)> Loaded = new(Load);

    private static readonly EvaluationOptions Options = new()
    {
        OutputFormat = OutputFormat.List,
        RequireFormatValidation = true,
    };

    /// <summary>Các <c>type</c> có schema (ví dụ <c>shp.scheduling.appointment-booked.v1</c>).</summary>
    public static IReadOnlyCollection<string> KnownTypes => Loaded.Value.Data.Keys;

    /// <summary>Danh sách lỗi; rỗng nếu hợp lệ. Type lạ cũng là lỗi.</summary>
    public static IReadOnlyList<string> Validate(CloudEvent evt)
    {
        ArgumentNullException.ThrowIfNull(evt);
        var errors = new List<string>();
        Collect(Loaded.Value.Envelope, evt.ToNode(), "envelope", errors);
        if (!Loaded.Value.Data.TryGetValue(evt.Type, out var schema))
        {
            errors.Add($"type chưa có schema: {evt.Type}");
            return errors;
        }

        Collect(schema, JsonNode.Parse(evt.Data.GetRawText()), "data", errors);
        return errors;
    }

    private static void Collect(JsonSchema schema, JsonNode? node, string where, List<string> errors)
    {
        var result = schema.Evaluate(node, Options);
        if (result.IsValid)
        {
            return;
        }

        foreach (var detail in result.Details.Where(d => d.Errors is { Count: > 0 }))
        {
            errors.AddRange(detail.Errors!.Select(e => $"{where}{detail.InstanceLocation}: {e.Value}"));
        }

        if (errors.Count == 0)
        {
            errors.Add($"{where}: không hợp lệ");
        }
    }

    private static (JsonSchema, Dictionary<string, JsonSchema>) Load()
    {
        var asm = typeof(EventSchemas).Assembly;
        JsonSchema Read(string name)
        {
            using var s = asm.GetManifestResourceStream(name) ?? throw new InvalidOperationException($"Thiếu resource {name}");
            using var r = new StreamReader(s);
            return JsonSchema.FromText(r.ReadToEnd());
        }

        var data = new Dictionary<string, JsonSchema>(StringComparer.Ordinal);
        foreach (var name in asm.GetManifestResourceNames().Where(n => n.StartsWith("events/", StringComparison.Ordinal) && n != "events/envelope.json"))
        {
            // events/<context>/<event>.v1.json → shp.<context>.<event>.v1
            var parts = name["events/".Length..].Replace('\\', '/').Replace(".json", "", StringComparison.Ordinal).Split('/');
            data["shp." + parts[0] + "." + parts[1]] = Read(name);
        }

        return (Read("events/envelope.json"), data);
    }
}
