using System.Net;
using System.Text.Json;
using Shp.Events;
using Xunit;

namespace Shp.Web.Tests;

public sealed class AuthzEndpointTests : IAsyncLifetime
{
    private const string PatientId = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
    private const string DoctorId = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
    private TestApp _app = null!;

    public async Task InitializeAsync()
    {
        _app = await TestApp.StartAsync();
        _app.Links.Link("pat", "Patient", PatientId);
        _app.Links.Link("doc", "Practitioner", DoctorId);
        _app.Links.Link("nurse", "Practitioner", "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03");
    }

    public async Task DisposeAsync() => await _app.DisposeAsync();

    private static string Doctor() => TestApp.Token(["doctor"], "doc", amr: ["mfa"]);

    private static string Nurse() => TestApp.Token(["nurse"], "nurse", amr: ["mfa"]);

    [Fact]
    public async Task Patient_reads_own_record_and_the_access_is_audited()
    {
        var r = await _app.GetAsync($"/api/v1/patients/{PatientId}", TestApp.Token(["patient"], "pat"));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);

        var rec = Assert.Single(_app.Audit.Records);
        Assert.Equal("success", rec.Outcome);
        Assert.Equal("read", rec.Action);
        Assert.Equal($"Patient/{PatientId}", rec.Resource);
        Assert.Equal("PATRQT", rec.PurposeOfUse);
        Assert.Equal("clinical", rec.Service);
        Assert.Equal(Guid.Parse(PatientId), rec.PatientId);
        Assert.Empty(EventSchemas.Validate(rec.ToCloudEvent()));
    }

    [Fact]
    public async Task Patient_reading_someone_else_is_403_and_denied_is_audited()
    {
        var r = await _app.GetAsync("/api/v1/patients/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5eff", TestApp.Token(["patient"], "pat"));
        Assert.Equal(HttpStatusCode.Forbidden, r.StatusCode);
        var rec = Assert.Single(_app.Audit.Records);
        Assert.Equal("denied", rec.Outcome);
        Assert.Equal("unmet-own", rec.Reason);
    }

    [Fact]
    public async Task Doctor_needs_a_care_relationship()
    {
        var path = $"/api/v1/patients/{PatientId}";
        var denied = await _app.GetAsync(path, Doctor());
        Assert.Equal(HttpStatusCode.Forbidden, denied.StatusCode);
        Assert.Equal("no-care-relationship", JsonDocument.Parse(await denied.Content.ReadAsStringAsync()).RootElement.GetProperty("code").GetString());

        _app.Facts.Care.Add((DoctorId, PatientId));
        Assert.Equal(HttpStatusCode.OK, (await _app.GetAsync(path, Doctor())).StatusCode);

        var records = _app.Audit.Records.ToList();
        Assert.Equal(["denied", "success"], records.Select(x => x.Outcome));
        Assert.Equal("TREAT", records[1].PurposeOfUse);
    }

    [Fact]
    public async Task Deny_as_not_found_hides_existence_but_still_audits()
    {
        var r = await _app.GetAsync($"/api/v1/hidden/{PatientId}", Doctor());
        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
        Assert.Equal("denied", Assert.Single(_app.Audit.Records).Outcome);
    }

    [Fact]
    public async Task Admin_can_never_read_clinical_data()
    {
        _app.Links.Link("adm", "Practitioner", "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e09");
        var r = await _app.GetAsync($"/api/v1/patients/{PatientId}", TestApp.Token(["admin"], "adm", amr: ["mfa"]));
        Assert.Equal(HttpStatusCode.Forbidden, r.StatusCode);
    }

    [Fact]
    public async Task Search_audit_records_parameter_names_but_never_values()
    {
        var r = await _app.GetAsync("/api/v1/patients?name=Nguyen%20Van%20A&phone=0901234567", Nurse());
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        var rec = Assert.Single(_app.Audit.Records);
        Assert.Equal("Patient?name&phone", rec.Resource);
        Assert.Equal("search", rec.Action);
        Assert.DoesNotContain("Nguyen", JsonSerializer.Serialize(rec), StringComparison.Ordinal);
        Assert.DoesNotContain("0901234567", JsonSerializer.Serialize(rec), StringComparison.Ordinal);
    }

    [Fact]
    public async Task Business_error_after_authorization_is_still_a_success_access_record()
    {
        var r = await _app.GetAsync("/api/v1/audited-conflict", Nurse());
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("success", Assert.Single(_app.Audit.Records).Outcome);
    }

    [Fact]
    public async Task Unexpected_failure_is_audited_as_error_without_leaking()
    {
        var r = await _app.GetAsync("/api/v1/audited-boom", Nurse());
        Assert.Equal(HttpStatusCode.InternalServerError, r.StatusCode);
        Assert.Equal("error", Assert.Single(_app.Audit.Records).Outcome);
        Assert.DoesNotContain("hunter2", await r.Content.ReadAsStringAsync(), StringComparison.Ordinal);
    }

    [Fact]
    public async Task Unauthenticated_request_never_reaches_handler_or_audit()
    {
        var r = await _app.GetAsync($"/api/v1/patients/{PatientId}");
        Assert.Equal(HttpStatusCode.Unauthorized, r.StatusCode);
        Assert.Empty(_app.Audit.Records);
    }

    [Fact]
    public void Scanner_finds_endpoints_without_a_declared_rule()
    {
        var unprotected = Shp.Web.Auth.AuthzEndpointExtensions.FindUnprotectedEndpoints(_app.Services);
        Assert.Contains("/api/v1/unprotected", unprotected);
        Assert.DoesNotContain("/api/v1/patients/{id}", unprotected);
        Assert.DoesNotContain("/api/v1/patients", unprotected);
    }
}
