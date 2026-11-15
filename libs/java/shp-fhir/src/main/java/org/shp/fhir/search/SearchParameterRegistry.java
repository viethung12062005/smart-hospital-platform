package org.shp.fhir.search;

import static org.shp.fhir.search.SearchParamDef.date;
import static org.shp.fhir.search.SearchParamDef.reference;
import static org.shp.fhir.search.SearchParamDef.string;
import static org.shp.fhir.search.SearchParamDef.token;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The supported search parameters per resource type (ADR-0003). A parameter not declared here → 400
 * {@code unsupported-parameter} (07 §4, strict). An endpoint may add its own constraints (a
 * required parameter, a maximum date range) after parsing.
 */
public final class SearchParameterRegistry {
  /** The in-scope resource type (01 §1). */
  public static final Set<String> RESOURCE_TYPES =
      Set.of(
          "Patient",
          "Practitioner",
          "PractitionerRole",
          "Schedule",
          "Slot",
          "Appointment",
          "Encounter",
          "Observation",
          "Condition",
          "AllergyIntolerance",
          "DocumentReference");

  private static final SearchParameterRegistry STANDARD =
      builder()
          // 08 S3
          .add("Schedule", reference("actor", "Practitioner"), token("specialty"), token("active"))
          // 08 S8
          .add(
              "Slot",
              token("status"),
              token("specialty"),
              reference("practitioner", "Practitioner"),
              reference("schedule", "Schedule"),
              date("start"))
          // 08 S13
          .add(
              "Appointment",
              reference("patient", "Patient"),
              reference("practitioner", "Practitioner"),
              token("status"),
              date("date"),
              token("specialty"))
          // 09 C6 (POST /fhir/Patient/_search)
          .add("Patient", string("name"), token("phone"), token("identifier"), date("birthdate"))
          // 09 C13
          .add(
              "Observation",
              reference("patient", "Patient"),
              reference("encounter", "Encounter"),
              token("category"),
              token("code"),
              date("date"))
          // 10 D4
          .add(
              "DocumentReference",
              reference("patient", "Patient"),
              reference("encounter", "Encounter"),
              token("type"),
              date("date"),
              token("status"))
          .build();

  private final Map<String, Map<String, SearchParamDef>> byType;

  private SearchParameterRegistry(Map<String, Map<String, SearchParamDef>> byType) {
    this.byType = byType;
  }

  /** The parameters per the API tables 08–10. */
  public static SearchParameterRegistry standard() {
    return STANDARD;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The parameter definition; empty when the resource type does not support it. */
  public Optional<SearchParamDef> find(String resourceType, String name) {
    return Optional.ofNullable(params(resourceType).get(name));
  }

  /**
   * All parameters of a resource type (empty when only {@code _count}/{@code _page_token} are
   * supported).
   */
  public Map<String, SearchParamDef> params(String resourceType) {
    if (!RESOURCE_TYPES.contains(resourceType)) {
      throw new IllegalArgumentException("Resource type out of scope: " + resourceType);
    }
    return byType.getOrDefault(resourceType, Map.of());
  }

  public static final class Builder {
    private final Map<String, Map<String, SearchParamDef>> byType = new LinkedHashMap<>();

    private Builder() {}

    public Builder add(String resourceType, SearchParamDef... defs) {
      if (!RESOURCE_TYPES.contains(resourceType)) {
        throw new IllegalArgumentException("Resource type out of scope: " + resourceType);
      }
      var params = byType.computeIfAbsent(resourceType, t -> new LinkedHashMap<>());
      for (SearchParamDef d : defs) {
        if (d.name().startsWith("_") || params.putIfAbsent(d.name(), d) != null) {
          throw new IllegalArgumentException("Duplicate or reserved parameter: " + d.name());
        }
      }
      return this;
    }

    public SearchParameterRegistry build() {
      Map<String, Map<String, SearchParamDef>> copy = new LinkedHashMap<>();
      byType.forEach((t, p) -> copy.put(t, Map.copyOf(p)));
      return new SearchParameterRegistry(Map.copyOf(copy));
    }
  }
}
