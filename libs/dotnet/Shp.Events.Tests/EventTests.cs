using System.Text.Json;
using Shp.Events;
using Xunit;

namespace Shp.Events.Tests;

public sealed class EventTests
{
    internal static string RepoFile(params string[] parts)
    {
        var dir = AppContext.BaseDirectory;
        while (dir is not null && !Directory.Exists(Path.Combine(dir, "contracts", "events")))
        {
            dir = Path.GetDirectoryName(dir);
        }

        return Path.Combine([dir ?? throw new InvalidOperationException("Không tìm thấy thư mục gốc repo"), .. parts]);
    }

    private static string[] AllExamples() => Directory.GetFiles(RepoFile("contracts", "events"), "*.example.json", SearchOption.AllDirectories);

    public static TheoryData<string> ExampleFiles() => [.. AllExamples()];

    private static string TypeOf(string exampleFile)
    {
        var ctx = Path.GetFileName(Path.GetDirectoryName(exampleFile))!;
        return "shp." + ctx + "." + Path.GetFileName(exampleFile).Replace(".example.json", "", StringComparison.Ordinal);
    }

    [Theory]
    [MemberData(nameof(ExampleFiles))]
    public void Every_contract_example_produces_a_valid_event(string file)
    {
        var data = JsonDocument.Parse(File.ReadAllText(file)).RootElement;
        var evt = CloudEvents.Create("test", TypeOf(file), "Thing/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70", data);
        Assert.Empty(EventSchemas.Validate(evt));
    }

    [Fact]
    public void Every_event_in_the_catalog_has_an_example_and_vice_versa()
    {
        var fromExamples = AllExamples().Select(TypeOf).Order().ToList();
        Assert.Equal(fromExamples, EventSchemas.KnownTypes.Order().ToList());
        Assert.Equal(11, fromExamples.Count);
    }

    [Fact]
    public void Missing_required_field_is_reported()
    {
        var evt = CloudEvents.Create("scheduling", "shp.scheduling.appointment-booked.v1", "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70",
            new { appointmentId = Guid.CreateVersion7(), version = 1 });
        var errors = EventSchemas.Validate(evt);
        Assert.NotEmpty(errors);
        Assert.Contains(errors, e => e.Contains("patientId", StringComparison.Ordinal));
    }

    [Fact]
    public void Wrong_format_is_reported()
    {
        var evt = CloudEvents.Create("scheduling", "shp.scheduling.appointment-checked-in.v1", "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70",
            new { appointmentId = "not-a-uuid", version = 1, patientId = Guid.CreateVersion7(), practitionerId = Guid.CreateVersion7(), specialty = "cardiology", checkedInAt = "yesterday" });
        Assert.NotEmpty(EventSchemas.Validate(evt));
    }

    [Fact]
    public void Unknown_event_type_is_an_error()
    {
        var evt = CloudEvents.Create("x", "shp.nothing.happened.v1", "Thing/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70", new { });
        Assert.Contains(EventSchemas.Validate(evt), e => e.Contains("chưa có schema", StringComparison.Ordinal));
    }

    [Fact]
    public void Bad_envelope_is_reported()
    {
        var evt = CloudEvents.Create("scheduling", "shp.scheduling.appointment-booked.v1", "not-a-subject", new { });
        Assert.Contains(EventSchemas.Validate(evt), e => e.StartsWith("envelope", StringComparison.Ordinal));
    }

    [Fact]
    public void Event_id_is_uuid_v7_time_is_utc_and_dataschema_derived()
    {
        var clock = new FixedClock(new DateTimeOffset(2026, 11, 20, 2, 15, 0, TimeSpan.Zero));
        var evt = CloudEvents.Create("scheduling", "shp.scheduling.appointment-booked.v1", "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70", new { }, clock);
        Assert.Equal('7', evt.Id[14]); // phiên bản UUID
        Assert.Equal(clock.GetUtcNow(), evt.Time);
        Assert.Equal("https://shp.example.org/schemas/scheduling/appointment-booked/v1.json", evt.DataSchema);
        Assert.Equal("/services/scheduling", evt.Source);
    }

    [Fact]
    public void Traceparent_comes_from_current_activity()
    {
        using var source = new System.Diagnostics.ActivitySource("test");
        using var listener = new System.Diagnostics.ActivityListener
        {
            ShouldListenTo = _ => true,
            Sample = (ref System.Diagnostics.ActivityCreationOptions<System.Diagnostics.ActivityContext> _) => System.Diagnostics.ActivitySamplingResult.AllDataAndRecorded,
        };
        System.Diagnostics.ActivitySource.AddActivityListener(listener);
        using var activity = source.StartActivity("op");
        var evt = CloudEvents.Create("s", "shp.scheduling.appointment-booked.v1", "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70", new { });
        Assert.Equal(activity!.Id, evt.TraceParent);
    }

    [Fact]
    public void Oversized_event_is_rejected()
    {
        var big = new { blob = new string('x', CloudEvents.MaxBytes) };
        Assert.Throws<ArgumentException>(() => CloudEvents.Create("s", "shp.scheduling.appointment-booked.v1", "A/1", big));
    }

    [Fact]
    public void Json_round_trip_preserves_the_event()
    {
        var evt = CloudEvents.Create("s", "shp.scheduling.appointment-booked.v1", "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70", new { a = 1 });
        var back = CloudEvents.Parse(evt.ToJson());
        Assert.Equal(evt.Id, back.Id);
        Assert.Equal(evt.Data.GetRawText(), back.Data.GetRawText());
    }

    [Fact]
    public void Invalid_type_name_cannot_be_created()
    {
        Assert.Throws<ArgumentException>(() => CloudEvents.Create("s", "appointment-booked", "A/1", new { }));
    }

    internal sealed class FixedClock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
