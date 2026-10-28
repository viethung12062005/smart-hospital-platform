using System.Reflection;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace Shp.Authorization;

/// <summary>Một dòng của ma trận: (resource, action, role) được phép khi mọi predicate trong <see cref="When"/> thỏa.</summary>
public sealed record AuthzRule(
    string Id,
    string Resource,
    string Action,
    string Role,
    IReadOnlyList<string> When,
    string? Purpose,
    IReadOnlyList<string> Fields,
    IReadOnlyList<string> Hide);

/// <summary>Ma trận phân quyền đã nạp và kiểm hợp lệ từ <c>contracts/authz/matrix.yaml</c> (bất biến, an toàn khi dùng đa luồng).</summary>
public sealed class AuthzMatrix
{
    private readonly Dictionary<(string Resource, string Action), AuthzRule[]> _index;

    private AuthzMatrix(IReadOnlyList<AuthzRule> rules, IReadOnlyDictionary<string, bool> clinicalByResource)
    {
        Rules = rules;
        ClinicalByResource = clinicalByResource;
        _index = rules
            .GroupBy(r => (r.Resource, r.Action))
            .ToDictionary(g => g.Key, g => g.ToArray());
    }

    public IReadOnlyList<AuthzRule> Rules { get; }

    /// <summary>Resource → có phải dữ liệu lâm sàng hay không.</summary>
    public IReadOnlyDictionary<string, bool> ClinicalByResource { get; }

    /// <summary>Các rule của (resource, action) theo đúng thứ tự trong file; rỗng nếu không có (mặc định từ chối).</summary>
    public IReadOnlyList<AuthzRule> RulesFor(string resource, string action) =>
        _index.TryGetValue((resource, action), out var rules) ? rules : [];

    /// <summary>Nạp ma trận được nhúng trong thư viện (đúng file <c>contracts/authz/matrix.yaml</c> lúc build).</summary>
    public static AuthzMatrix LoadEmbedded()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("matrix.yaml")
            ?? throw new InvalidOperationException("Thiếu resource matrix.yaml trong Shp.Authorization");
        using var reader = new StreamReader(stream);
        return Parse(reader.ReadToEnd());
    }

    /// <summary>Phân tích và kiểm tra YAML; ném <see cref="AuthzMatrixException"/> với danh sách lỗi nếu vi phạm bất biến an toàn.</summary>
    public static AuthzMatrix Parse(string yaml)
    {
        var deserializer = new DeserializerBuilder().WithNamingConvention(CamelCaseNamingConvention.Instance).IgnoreUnmatchedProperties().Build();
        var doc = deserializer.Deserialize<MatrixDocument>(yaml) ?? throw new AuthzMatrixException(["File rỗng"]);
        var errors = Validate(doc);
        if (errors.Count > 0)
        {
            throw new AuthzMatrixException(errors);
        }

        var rules = doc.Rules.Select(r => new AuthzRule(
            r.Id, r.Resource, r.Action, r.Role, [.. r.When], r.Purpose, [.. r.Fields], [.. r.Hide])).ToList();
        return new AuthzMatrix(rules, doc.Resources.ToDictionary(kv => kv.Key, kv => kv.Value.Clinical));
    }

    private static List<string> Validate(MatrixDocument doc)
    {
        var errors = new List<string>();
        var roles = doc.Roles.ToHashSet();
        var predicates = doc.Predicates.Keys.ToHashSet();
        var purposes = doc.Purposes.ToHashSet();
        var deny = doc.DenyAll.Select(d => (d.Resource, d.Action)).ToHashSet();
        var ids = new HashSet<string>();
        var covered = new HashSet<(string, string)>();

        foreach (var r in doc.Rules)
        {
            if (!ids.Add(r.Id))
            {
                errors.Add($"{r.Id}: id trùng");
            }

            if (!doc.Resources.TryGetValue(r.Resource, out var spec))
            {
                errors.Add($"{r.Id}: resource lạ {r.Resource}");
                continue;
            }

            if (!spec.Actions.Contains(r.Action))
            {
                errors.Add($"{r.Id}: action {r.Action} không khai báo cho {r.Resource}");
            }

            if (!roles.Contains(r.Role))
            {
                errors.Add($"{r.Id}: role lạ {r.Role}");
            }

            errors.AddRange(r.When.Where(p => !predicates.Contains(p)).Select(p => $"{r.Id}: predicate lạ {p}"));
            if (r.Purpose is not null && !purposes.Contains(r.Purpose))
            {
                errors.Add($"{r.Id}: purpose lạ {r.Purpose}");
            }

            if (deny.Contains((r.Resource, r.Action)))
            {
                errors.Add($"{r.Id}: vi phạm denyAll");
            }

            if (spec.Clinical && r.Role is "admin" or "dpo")
            {
                errors.Add($"{r.Id}: admin/dpo không được chạm dữ liệu lâm sàng");
            }

            if (r.Role == "dpo" && r.Resource != "Audit")
            {
                errors.Add($"{r.Id}: dpo chỉ được có rule cho Audit");
            }

            if (r.When.Contains(Predicates.BreakGlass) && r.Role != "doctor")
            {
                errors.Add($"{r.Id}: break-glass chỉ dành cho doctor");
            }

            if (r.When.Contains(Predicates.Own) && r.Role != "patient")
            {
                errors.Add($"{r.Id}: predicate own chỉ dành cho patient");
            }

            covered.Add((r.Resource, r.Action));
        }

        foreach (var (resource, spec) in doc.Resources)
        {
            errors.AddRange(spec.Actions
                .Where(a => !covered.Contains((resource, a)) && !deny.Contains((resource, a)))
                .Select(a => $"({resource},{a}): không có rule và không nằm trong denyAll"));
        }

        return errors;
    }

#pragma warning disable CA1812 // được tạo bởi YamlDotNet
    private sealed class MatrixDocument
    {
        public List<string> Roles { get; set; } = [];

        public List<string> Purposes { get; set; } = [];

        public Dictionary<string, string> Predicates { get; set; } = [];

        public Dictionary<string, ResourceSpec> Resources { get; set; } = [];

        public List<ResourceAction> DenyAll { get; set; } = [];

        public List<RuleDto> Rules { get; set; } = [];
    }

    private sealed class ResourceSpec
    {
        public bool Clinical { get; set; }

        public List<string> Actions { get; set; } = [];
    }

    private sealed class ResourceAction
    {
        public string Resource { get; set; } = "";

        public string Action { get; set; } = "";
    }

    private sealed class RuleDto
    {
        public string Id { get; set; } = "";

        public string Resource { get; set; } = "";

        public string Action { get; set; } = "";

        public string Role { get; set; } = "";

        public List<string> When { get; set; } = [];

        public string? Purpose { get; set; }

        public List<string> Fields { get; set; } = [];

        public List<string> Hide { get; set; } = [];
    }
#pragma warning restore CA1812
}

public sealed class AuthzMatrixException(IReadOnlyList<string> errors)
    : Exception("Ma trận phân quyền không hợp lệ: " + string.Join("; ", errors))
{
    public IReadOnlyList<string> Errors { get; } = errors;
}
