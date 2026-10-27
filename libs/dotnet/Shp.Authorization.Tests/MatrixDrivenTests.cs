using Shp.Authorization;
using Xunit;

namespace Shp.Authorization.Tests;

/// <summary>Test sinh từ chính matrix.yaml: sửa ma trận thì test tự đổi theo, thêm rule mà engine hành xử sai là đỏ.</summary>
public sealed class MatrixDrivenTests
{
    private static readonly AuthzMatrix Matrix = AuthzMatrix.LoadEmbedded();
    private static readonly DateTimeOffset Now = new(2026, 11, 20, 8, 0, 0, TimeSpan.Zero);
    private static readonly AuthorizationEngine Engine = new(Matrix, new FixedClock(Now));
    private static readonly string[] Roles = ["patient", "doctor", "nurse", "admin", "dpo"];
    private static readonly string[] AllPredicates =
    [
        Predicates.Own, Predicates.SelfPractitioner, Predicates.Care, Predicates.BreakGlass, Predicates.Released,
        Predicates.EncounterFinished, Predicates.UnfinishedEncounter, Predicates.Before2h, Predicates.NotCare,
    ];

    public static TheoryData<string> RuleIds() => [.. Matrix.Rules.Select(r => r.Id)];

    public static TheoryData<string, string, string> UncoveredTriples()
    {
        var data = new TheoryData<string, string, string>();
        var covered = Matrix.Rules.Select(r => (r.Resource, r.Action, r.Role)).ToHashSet();
        foreach (var rule in Matrix.Rules.Select(r => (r.Resource, r.Action)).Distinct())
        {
            foreach (var role in Roles.Where(role => !covered.Contains((rule.Resource, rule.Action, role))))
            {
                data.Add(rule.Resource, rule.Action, role);
            }
        }

        return data;
    }

    [Theory]
    [MemberData(nameof(RuleIds))]
    public void Rule_allows_when_all_predicates_hold(string ruleId)
    {
        var rule = Matrix.Rules.Single(r => r.Id == ruleId);
        var (principal, ctx) = Build(rule.Role, holding: rule.When);

        var d = Engine.Decide(principal, rule.Resource, rule.Action, ctx);

        Assert.True(d.Allowed, $"{ruleId} phải cho phép: {d.DenyReason}");
    }

    [Theory]
    [MemberData(nameof(RuleIds))]
    public void Rule_denies_when_any_single_predicate_fails(string ruleId)
    {
        var rule = Matrix.Rules.Single(r => r.Id == ruleId);
        foreach (var failing in rule.When)
        {
            var (principal, ctx) = Build(rule.Role, holding: rule.When.Where(p => p != failing).ToList(), notCareFails: failing == Predicates.NotCare);

            var d = Engine.Decide(principal, rule.Resource, rule.Action, ctx);

            Assert.False(d.Allowed, $"{ruleId} không được cho phép khi thiếu {failing}");
        }
    }

    [Theory]
    [MemberData(nameof(UncoveredTriples))]
    public void Default_deny_for_roles_without_rule_even_when_every_predicate_holds(string resource, string action, string role)
    {
        var (principal, ctx) = Build(role, holding: AllPredicates);

        var d = Engine.Decide(principal, resource, action, ctx);

        Assert.False(d.Allowed, $"{role} không có rule cho {resource}.{action}");
    }

    [Fact]
    public void Admin_and_dpo_never_reach_clinical_resources()
    {
        foreach (var (resource, clinical) in Matrix.ClinicalByResource.Where(kv => kv.Value))
        {
            foreach (var action in Matrix.Rules.Where(r => r.Resource == resource).Select(r => r.Action).Distinct())
            {
                foreach (var role in new[] { "admin", "dpo" })
                {
                    var (principal, ctx) = Build(role, holding: AllPredicates);
                    Assert.False(Engine.Decide(principal, resource, action, ctx).Allowed, $"{role} chạm {resource}.{action} ({clinical})");
                }
            }
        }
    }

    [Fact]
    public void Audit_update_and_delete_are_denied_for_everyone()
    {
        foreach (var role in Roles)
        {
            var (principal, ctx) = Build(role, holding: AllPredicates);
            Assert.False(Engine.Decide(principal, "Audit", "update", ctx).Allowed);
            Assert.False(Engine.Decide(principal, "Audit", "delete", ctx).Allowed);
        }
    }

    [Fact]
    public void Unknown_resource_or_action_is_denied()
    {
        var (principal, ctx) = Build("nurse", holding: AllPredicates);
        Assert.Equal("no-rule", Engine.Decide(principal, "Nope", "read", ctx).DenyReason);
        Assert.Equal("no-rule", Engine.Decide(principal, "Patient", "teleport", ctx).DenyReason);
    }

    [Fact]
    public void Doctor_without_care_gets_no_care_relationship_reason()
    {
        var (principal, ctx) = Build("doctor", holding: []);
        var d = Engine.Decide(principal, "Patient", "read", ctx);
        Assert.False(d.Allowed);
        Assert.Equal("no-care-relationship", d.DenyReason);
    }

    [Fact]
    public void Care_takes_precedence_over_breakglass_for_purpose()
    {
        var (principal, ctx) = Build("doctor", holding: [Predicates.Care, Predicates.BreakGlass]);
        var d = Engine.Decide(principal, "Patient", "read", ctx);
        Assert.True(d.Allowed);
        Assert.Equal("TREAT", d.Purpose);
    }

    [Fact]
    public void Breakglass_alone_allows_with_emergency_purpose()
    {
        var (principal, ctx) = Build("doctor", holding: [Predicates.BreakGlass]);
        var d = Engine.Decide(principal, "Patient", "read", ctx);
        Assert.True(d.Allowed);
        Assert.Equal("ETREAT", d.Purpose);
    }

    [Fact]
    public void Decision_carries_field_and_hide_constraints()
    {
        var (patient, pctx) = Build("patient", holding: [Predicates.Own]);
        Assert.Contains("telecom", Engine.Decide(patient, "Patient", "update", pctx).Fields);
        var (admin, actx) = Build("admin", holding: []);
        Assert.Contains("reason", Engine.Decide(admin, "Audit", "read", actx).Hide);
    }

    [Fact]
    public void Cancel_window_is_exactly_two_hours()
    {
        var principal = new Principal("s", ["patient"], "p1");
        Allowed(Now.AddHours(2));
        Denied(Now.AddHours(2).AddSeconds(-1));

        void Allowed(DateTimeOffset start) => Assert.True(Engine.Decide(principal, "Appointment", "cancel", Ctx(start)).Allowed);
        void Denied(DateTimeOffset start) => Assert.False(Engine.Decide(principal, "Appointment", "cancel", Ctx(start)).Allowed);
        static ResourceContext Ctx(DateTimeOffset start) => new() { PatientId = "p1", AppointmentStart = start };
    }

    [Fact]
    public void Unlinked_user_is_denied_own_access()
    {
        var principal = new Principal("s", ["patient"], null);
        Assert.False(Engine.Decide(principal, "Patient", "read", new ResourceContext { PatientId = null }).Allowed);
    }

    [Fact]
    public void Multiple_roles_use_any_matching_rule()
    {
        var principal = new Principal("s", ["patient", "nurse"], "x");
        Assert.True(Engine.Decide(principal, "Slot", "create", new ResourceContext()).Allowed);
    }

    [Fact]
    public void Loader_rejects_unsafe_matrix()
    {
        var yaml = """
            roles: [admin]
            purposes: [TREAT]
            predicates: { own: x }
            resources: { Patient: { clinical: true, actions: [read] } }
            denyAll: []
            rules:
              - { id: BAD, resource: Patient, action: read, role: admin, when: [nope] }
            """;
        var ex = Assert.Throws<AuthzMatrixException>(() => AuthzMatrix.Parse(yaml));
        Assert.Contains(ex.Errors, e => e.Contains("lâm sàng", StringComparison.Ordinal));
        Assert.Contains(ex.Errors, e => e.Contains("predicate lạ", StringComparison.Ordinal));
    }

    [Fact]
    public void Embedded_matrix_equals_the_contract_file()
    {
        var dir = AppContext.BaseDirectory;
        while (dir is not null && !File.Exists(Path.Combine(dir, "contracts", "authz", "matrix.yaml")))
        {
            dir = Path.GetDirectoryName(dir);
        }

        Assert.NotNull(dir);
        var onDisk = AuthzMatrix.Parse(File.ReadAllText(Path.Combine(dir, "contracts", "authz", "matrix.yaml")));
        Assert.Equal(onDisk.Rules.Select(r => r.Id), Matrix.Rules.Select(r => r.Id));
    }

    /// <summary>Dựng người gọi và ngữ cảnh sao cho ĐÚNG các predicate trong <paramref name="holding"/> thỏa, mọi predicate khác không thỏa.</summary>
    /// <param name="notCareFails">`care` và `not-care` loại trừ nhau; đặt true để dựng ngữ cảnh mà `not-care` không thỏa (bác sĩ có quan hệ điều trị).</param>
    private static (Principal, ResourceContext) Build(string role, IReadOnlyCollection<string> holding, bool notCareFails = false)
    {
        var principal = new Principal("subject-1", [role], "me");
        var ctx = new ResourceContext
        {
            PatientId = holding.Contains(Predicates.Own) ? "me" : "someone-else",
            PractitionerId = holding.Contains(Predicates.SelfPractitioner) ? "me" : "someone-else",
            HasCareRelationship = holding.Contains(Predicates.Care) || notCareFails,
            HasActiveBreakGlass = holding.Contains(Predicates.BreakGlass),
            ReleasedToPatient = holding.Contains(Predicates.Released),
            EncounterFinished = holding.Contains(Predicates.EncounterFinished) ? true
                : holding.Contains(Predicates.UnfinishedEncounter) ? false : null,
            AppointmentStart = holding.Contains(Predicates.Before2h) ? Now.AddHours(3) : Now.AddHours(1),
        };
        return (principal, ctx);
    }

    private sealed class FixedClock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
