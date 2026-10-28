package org.shp.authz;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Ma trận phân quyền đã nạp và kiểm hợp lệ từ {@code contracts/authz/matrix.yaml} (bất biến, an
 * toàn đa luồng). Các bất biến giống {@code tools/contracts/validate_authz.py}.
 */
public final class AuthzMatrix {
  static final String RESOURCE = "shp/authz/matrix.yaml";

  private final List<AuthzRule> rules;
  private final Map<String, Boolean> clinicalByResource;
  private final Map<String, List<AuthzRule>> index;

  private AuthzMatrix(List<AuthzRule> rules, Map<String, Boolean> clinicalByResource) {
    this.rules = List.copyOf(rules);
    this.clinicalByResource = Map.copyOf(clinicalByResource);
    Map<String, List<AuthzRule>> idx = new LinkedHashMap<>();
    for (AuthzRule r : rules) {
      idx.computeIfAbsent(key(r.resource(), r.action()), k -> new ArrayList<>()).add(r);
    }
    this.index =
        idx.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
  }

  public List<AuthzRule> rules() {
    return rules;
  }

  /** Resource → có phải dữ liệu lâm sàng hay không. */
  public Map<String, Boolean> clinicalByResource() {
    return clinicalByResource;
  }

  /**
   * Rule của (resource, action) theo đúng thứ tự trong file; rỗng nếu không có (mặc định từ chối).
   */
  public List<AuthzRule> rulesFor(String resource, String action) {
    return index.getOrDefault(key(resource, action), List.of());
  }

  /** Nạp ma trận được nhúng trong thư viện (đúng file contracts/authz/matrix.yaml lúc build). */
  public static AuthzMatrix loadEmbedded() {
    try (InputStream in = AuthzMatrix.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu resource " + RESOURCE + " trong shp-authz");
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Phân tích và kiểm YAML; ném {@link AuthzMatrixException} nếu vi phạm bất biến an toàn. */
  public static AuthzMatrix parse(String yaml) {
    Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    if (!(loaded instanceof Map<?, ?> doc)) {
      throw new AuthzMatrixException(List.of("File rỗng hoặc không phải mapping"));
    }
    Set<String> roles = Set.copyOf(strings(doc.get("roles")));
    Set<String> purposes = Set.copyOf(strings(doc.get("purposes")));
    Set<String> predicates =
        Set.copyOf(map(doc.get("predicates")).keySet().stream().map(String::valueOf).toList());
    Map<String, Boolean> clinical = new LinkedHashMap<>();
    Map<String, List<String>> actions = new LinkedHashMap<>();
    map(doc.get("resources"))
        .forEach(
            (k, v) -> {
              Map<?, ?> spec = map(v);
              clinical.put(String.valueOf(k), Boolean.TRUE.equals(spec.get("clinical")));
              actions.put(String.valueOf(k), strings(spec.get("actions")));
            });
    Set<String> deny = new HashSet<>();
    for (Object d : list(doc.get("denyAll"))) {
      Map<?, ?> m = map(d);
      deny.add(key(String.valueOf(m.get("resource")), String.valueOf(m.get("action"))));
    }

    List<String> errors = new ArrayList<>();
    List<AuthzRule> rules = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    Set<String> covered = new HashSet<>();
    for (Object o : list(doc.get("rules"))) {
      Map<?, ?> r = map(o);
      AuthzRule rule =
          new AuthzRule(
              str(r.get("id")),
              str(r.get("resource")),
              str(r.get("action")),
              str(r.get("role")),
              strings(r.get("when")),
              r.get("purpose") == null ? null : str(r.get("purpose")),
              strings(r.get("fields")),
              strings(r.get("hide")));
      validateRule(rule, roles, purposes, predicates, clinical, actions, deny, ids, errors);
      covered.add(key(rule.resource(), rule.action()));
      rules.add(rule);
    }
    actions.forEach(
        (res, acts) -> {
          for (String a : acts) {
            if (!covered.contains(key(res, a)) && !deny.contains(key(res, a))) {
              errors.add("(" + res + "," + a + "): không có rule và không nằm trong denyAll");
            }
          }
        });
    if (!errors.isEmpty()) {
      throw new AuthzMatrixException(errors);
    }
    return new AuthzMatrix(rules, clinical);
  }

  private static void validateRule(
      AuthzRule r,
      Set<String> roles,
      Set<String> purposes,
      Set<String> predicates,
      Map<String, Boolean> clinical,
      Map<String, List<String>> actions,
      Set<String> deny,
      Set<String> ids,
      List<String> errors) {
    String id = r.id();
    if (!ids.add(id)) {
      errors.add(id + ": id trùng");
    }
    if (!actions.containsKey(r.resource())) {
      errors.add(id + ": resource lạ " + r.resource());
      return;
    }
    if (!actions.get(r.resource()).contains(r.action())) {
      errors.add(id + ": action " + r.action() + " không khai báo cho " + r.resource());
    }
    if (!roles.contains(r.role())) {
      errors.add(id + ": role lạ " + r.role());
    }
    r.when().stream()
        .filter(p -> !predicates.contains(p))
        .forEach(p -> errors.add(id + ": predicate lạ " + p));
    if (r.purpose() != null && !purposes.contains(r.purpose())) {
      errors.add(id + ": purpose lạ " + r.purpose());
    }
    if (deny.contains(key(r.resource(), r.action()))) {
      errors.add(id + ": vi phạm denyAll");
    }
    if (clinical.get(r.resource()) && (r.role().equals("admin") || r.role().equals("dpo"))) {
      errors.add(id + ": admin/dpo không được chạm dữ liệu lâm sàng");
    }
    if (r.role().equals("dpo") && !r.resource().equals("Audit")) {
      errors.add(id + ": dpo chỉ được có rule cho Audit");
    }
    if (r.when().contains(Predicates.BREAK_GLASS) && !r.role().equals("doctor")) {
      errors.add(id + ": break-glass chỉ dành cho doctor");
    }
    if (r.when().contains(Predicates.OWN) && !r.role().equals("patient")) {
      errors.add(id + ": predicate own chỉ dành cho patient");
    }
  }

  private static String key(String resource, String action) {
    return resource + "\u0000" + action;
  }

  private static Map<?, ?> map(Object o) {
    return o instanceof Map<?, ?> m ? m : Map.of();
  }

  private static List<?> list(Object o) {
    return o instanceof List<?> l ? l : List.of();
  }

  private static List<String> strings(Object o) {
    return list(o).stream().map(String::valueOf).toList();
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }
}
