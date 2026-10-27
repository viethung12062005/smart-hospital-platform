namespace Shp.Authorization;

/// <summary>
/// Quyết định Allow/Deny theo <see cref="AuthzMatrix"/>. Mặc định TỪ CHỐI: chỉ cho phép khi có rule khớp và mọi predicate của rule thỏa.
/// Nhiều rule khớp: lấy rule đầu tiên theo thứ tự trong file (file đặt rule thường trước break-glass nên purpose là TREAT khi cả hai thỏa).
/// </summary>
public sealed class AuthorizationEngine(AuthzMatrix matrix, TimeProvider clock)
{
    private static readonly TimeSpan CancelWindow = TimeSpan.FromHours(2);

    public AuthzDecision Decide(Principal principal, string resource, string action, ResourceContext context)
    {
        ArgumentNullException.ThrowIfNull(principal);
        ArgumentNullException.ThrowIfNull(context);

        var rules = matrix.RulesFor(resource, action);
        if (rules.Count == 0)
        {
            return AuthzDecision.Deny("no-rule");
        }

        string? firstUnmet = null;
        foreach (var rule in rules.Where(r => principal.Roles.Contains(r.Role)))
        {
            var unmet = rule.When.FirstOrDefault(p => !Holds(p, principal, context));
            if (unmet is null)
            {
                return new AuthzDecision(true, rule.Id, rule.Purpose, rule.Fields, rule.Hide, null);
            }

            firstUnmet ??= unmet;
        }

        return AuthzDecision.Deny(firstUnmet switch
        {
            null => "role-not-allowed",
            Predicates.Care => "no-care-relationship",
            _ => "unmet-" + firstUnmet,
        });
    }

    private bool Holds(string predicate, Principal principal, ResourceContext c) => predicate switch
    {
        Predicates.Own => Same(c.PatientId, principal.FhirUserId),
        Predicates.SelfPractitioner => Same(c.PractitionerId, principal.FhirUserId),
        Predicates.Care => c.HasCareRelationship,
        Predicates.BreakGlass => c.HasActiveBreakGlass,
        Predicates.NotCare => !c.HasCareRelationship,
        Predicates.Released => c.ReleasedToPatient == true,
        Predicates.EncounterFinished => c.EncounterFinished == true,
        Predicates.UnfinishedEncounter => c.EncounterFinished == false,
        Predicates.Before2h => c.AppointmentStart is { } start && start - clock.GetUtcNow() >= CancelWindow,
        _ => false, // predicate lạ: từ chối
    };

    private static bool Same(string? a, string? b) => a is not null && b is not null && string.Equals(a, b, StringComparison.Ordinal);
}
