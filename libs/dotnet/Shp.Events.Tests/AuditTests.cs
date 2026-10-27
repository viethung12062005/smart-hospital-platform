using Shp.Authorization;
using Shp.Events;
using Shp.Events.Audit;
using Xunit;

namespace Shp.Events.Tests;

public sealed class AuditTests
{
    private static readonly TimeProvider Clock = new EventTests.FixedClock(new DateTimeOffset(2026, 11, 20, 2, 15, 0, TimeSpan.Zero));
    private static readonly AuditActor Doctor = new("kc-doctor", "doctor", "Practitioner/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02");
    private static readonly Guid Patient = Guid.Parse("0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01");

    private static AuditRecord Build(string resource = "Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e05", string? ip = "10.1.2.3", string? reason = null, string outcome = "success") =>
        AuditRecordBuilder.Build(Clock, "clinical", AuditActions.Read, outcome, Doctor, resource, Patient, "TREAT", reason, "4bf92f3577b34da6a3ce929d0e0e4736", ip, "Mozilla/5.0");

    [Fact]
    public void Built_record_is_a_valid_E11_event()
    {
        var evt = Build().ToCloudEvent(Clock);
        Assert.Equal("shp.audit.recorded.v1", evt.Type);
        Assert.Empty(EventSchemas.Validate(evt));
    }

    [Theory]
    [InlineData("10.1.2.3", "10.1.2.x")]
    [InlineData("192.168.255.254", "192.168.255.x")]
    [InlineData("::ffff:10.9.8.7", "10.9.8.x")]
    [InlineData("2001:db8:1:2:3:4:5:6", "2001:0db8:0001::x")]
    public void Ip_is_masked(string ip, string expected) => Assert.Equal(expected, AuditRecordBuilder.MaskIp(ip));

    [Theory]
    [InlineData("not-an-ip")]
    [InlineData("")]
    [InlineData(null)]
    public void Garbage_ip_is_not_stored(string? ip) => Assert.Null(AuditRecordBuilder.MaskIp(ip));

    [Theory]
    [InlineData("Patient?name=Nguyen")]
    [InlineData("Patient?phone=0901234567")]
    [InlineData("Patient/0192 f8a4")]
    [InlineData("Patient search")]
    [InlineData("")]
    public void Resource_with_values_or_spaces_is_rejected(string resource) =>
        Assert.Throws<ArgumentException>(() => Build(resource));

    [Theory]
    [InlineData("Patient")]
    [InlineData("Patient?name&birthdate")]
    [InlineData("Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70")]
    public void Resource_with_type_id_or_parameter_names_is_accepted(string resource) => Assert.Equal(resource, Build(resource).Resource);

    [Fact]
    public void Long_reason_and_long_user_agent_are_handled()
    {
        Assert.Throws<ArgumentException>(() => Build(reason: new string('x', AuditRecordBuilder.MaxReasonLength + 1)));
        var r = AuditRecordBuilder.Build(Clock, "s", "read", "success", Doctor, "Patient", userAgent: new string('u', 500));
        Assert.Equal(AuditRecordBuilder.MaxUserAgentLength, r.Client!.UserAgent!.Length);
    }

    [Fact]
    public void Unknown_action_or_outcome_is_rejected()
    {
        Assert.Throws<ArgumentException>(() => AuditRecordBuilder.Build(Clock, "s", "peek", "success", Doctor, "Patient"));
        Assert.Throws<ArgumentException>(() => AuditRecordBuilder.Build(Clock, "s", "read", "maybe", Doctor, "Patient"));
    }

    [Fact]
    public void Every_matrix_action_maps_to_a_valid_audit_action()
    {
        var matrix = AuthzMatrix.LoadEmbedded();
        foreach (var action in matrix.Rules.Select(r => r.Action).Distinct())
        {
            Assert.True(AuthzAudit.ActionMap.TryGetValue(action, out var mapped), $"Thiếu ánh xạ audit cho hành động '{action}'");
            Assert.Contains(mapped!, AuditActions.All);
        }
    }

    [Fact]
    public void Denied_decision_is_audited_with_reason_and_allowed_with_purpose()
    {
        var principal = new Principal("kc-doctor", ["doctor"], "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02");
        var denied = AuthzAudit.FromDecision(Clock, "clinical", principal, "Practitioner", "read", "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01",
            AuthzDecision.Deny("no-care-relationship"), Patient, null, "10.0.0.5", "ua");
        Assert.Equal("denied", denied.Outcome);
        Assert.Equal("no-care-relationship", denied.Reason);
        Assert.Equal("Practitioner/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02", denied.Actor.FhirUser);

        var allowed = AuthzAudit.FromDecision(Clock, "clinical", principal, "Practitioner", "read", "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01",
            new AuthzDecision(true, "PAT-READ-DOC", "TREAT", [], [], null), Patient, null, "10.0.0.5", "ua");
        Assert.Equal("success", allowed.Outcome);
        Assert.Equal("TREAT", allowed.PurposeOfUse);
        Assert.Null(allowed.Reason);
        Assert.Empty(EventSchemas.Validate(allowed.ToCloudEvent(Clock)));
    }

    [Fact]
    public void Actor_role_prefers_the_most_privileged()
    {
        var principal = new Principal("s", ["patient", "nurse"], null);
        var r = AuthzAudit.FromDecision(Clock, "s", principal, "Practitioner", "read", "Patient", new AuthzDecision(true, "X", null, [], [], null), null, null, null, null);
        Assert.Equal("nurse", r.Actor.Role);
        Assert.Null(r.Actor.FhirUser);
    }
}
