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
 * Authorization matrix loaded and validated from {@code contracts/authz/matrix.yaml} (immutable,
 * thread-safe). The invariants match {@code tools/contracts/validate_authz.py}.
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

  /** Resource → whether it is clinical data. */
  public Map<String, Boolean> clinicalByResource() {
    return clinicalByResource;
  }

  /** Rules for (resource, action) in file order; empty if none (deny by default). */
  public List<AuthzRule> rulesFor(String resource, String action) {
    return index.getOrDefault(key(resource, action), List.of());
  }

  /**
   * Load the matrix embedded in the library (the exact contracts/authz/matrix.yaml at build time).
   */
  public static AuthzMatrix loadEmbedded() {
    try (InputStream in = AuthzMatrix.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + RESOURCE + " in shp-authz");
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Parse and validate the YAML; throws {@link AuthzMatrixException} if a safety invariant is
   * violated.
   */
  public static AuthzMatrix parse(String yaml) {
    Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    if (!(loaded instanceof Map<?, ?> doc)) {
      throw new AuthzMatrixException(List.of("File is empty or not a mapping"));
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
              errors.add("(" + res + "," + a + "): no rule and not listed in denyAll");
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
      errors.add(id + ": duplicate id");
    }
    if (!actions.containsKey(r.resource())) {
      errors.add(id + ": unknown resource " + r.resource());
      return;
    }
    if (!actions.get(r.resource()).contains(r.action())) {
      errors.add(id + ": action " + r.action() + " is not declared for " + r.resource());
    }
    if (!roles.contains(r.role())) {
      errors.add(id + ": unknown role " + r.role());
    }
    r.when().stream()
        .filter(p -> !predicates.contains(p))
        .forEach(p -> errors.add(id + ": unknown predicate " + p));
    if (r.purpose() != null && !purposes.contains(r.purpose())) {
      errors.add(id + ": unknown purpose " + r.purpose());
    }
    if (deny.contains(key(r.resource(), r.action()))) {
      errors.add(id + ": violates denyAll");
    }
    if (clinical.get(r.resource()) && (r.role().equals("admin") || r.role().equals("dpo"))) {
      errors.add(id + ": admin/dpo must not touch clinical data");
    }
    if (r.role().equals("dpo") && !r.resource().equals("Audit")) {
      errors.add(id + ": dpo may only have rules for Audit");
    }
    if (r.when().contains(Predicates.BREAK_GLASS) && !r.role().equals("doctor")) {
      errors.add(id + ": break-glass is for doctor only");
    }
    if (r.when().contains(Predicates.OWN) && !r.role().equals("patient")) {
      errors.add(id + ": predicate own is for patient only");
    }
    if ((r.when().contains(Predicates.LINKED)
            || r.when().contains(Predicates.LINKED_RECORDS)
            || r.when().contains(Predicates.GUARDIAN))
        && !r.role().equals("patient")) {
      errors.add(id + ": predicates linked, linked-records, guardian are for patient only");
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
