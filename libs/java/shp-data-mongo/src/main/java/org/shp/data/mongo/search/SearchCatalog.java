package org.shp.data.mongo.search;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The search parameters the Mongo executor supports, their normalized field in the stored document
 * (03-db-clinical-document §2.2, §3) and the index that serves each main query.
 *
 * <p>Only resource types stored in MongoDB are listed. A resource owned by PostgreSQL (Schedule,
 * Slot, Appointment) is rejected: its search is compiled by the scheduling repository instead.
 */
public final class SearchCatalog {
  /** System of the patient identifier that is stored in {@code search.mrn} (09 C6). */
  public static final String MRN_SYSTEM = "urn:shp:mrn";

  /** How a criterion is matched against the normalized field. */
  public enum Kind {
    /** Multikey array of normalized name tokens; the last token matches by anchored prefix. */
    NAME,
    /** Code value; the normalized field does not keep the FHIR system. */
    TOKEN,
    /** Resource id of a reference, without the {@code Type/} prefix. */
    REFERENCE,
    /** BSON date; ranges are half-open {@code [from, to)}. */
    DATE
  }

  /**
   * A parameter bound to a field.
   *
   * @param path dotted path in the stored document
   * @param kind matching rule
   */
  public record Field(String path, Kind kind) {}

  /**
   * A hint rule: when any of {@code params} is present, the query uses {@code index}.
   *
   * @param params search parameters that justify the index
   * @param index name of an index created by {@link SearchIndexes}
   */
  public record Hint(Set<String> params, String index) {}

  /**
   * One Mongo-backed resource type.
   *
   * @param resourceType FHIR resource type
   * @param collection Mongo collection
   * @param fields supported parameters
   * @param hints hint rules, highest priority first
   */
  public record Collection(
      String resourceType, String collection, Map<String, Field> fields, List<Hint> hints) {

    public Field field(String param) {
      return fields.get(param);
    }

    /** The hint for the present parameters, or null when no index is suitable. */
    public String indexFor(Set<String> presentParams) {
      return hints.stream()
          .filter(h -> !Collections.disjoint(h.params(), presentParams))
          .map(Hint::index)
          .findFirst()
          .orElse(null);
    }
  }

  private final Map<String, Collection> byType;

  private SearchCatalog(Map<String, Collection> byType) {
    this.byType = Map.copyOf(byType);
  }

  /**
   * The catalog for the search parameters declared by {@code SearchParameterRegistry.standard()}.
   */
  public static SearchCatalog standard() {
    Map<String, Collection> types = new LinkedHashMap<>();

    types.put(
        "Patient",
        new Collection(
            "Patient",
            SearchIndexes.PATIENTS,
            Map.of(
                "name", new Field("search.nameTokens", Kind.NAME),
                "phone", new Field("search.phone", Kind.TOKEN),
                "identifier", new Field("search.mrn", Kind.TOKEN),
                "birthdate", new Field("search.birthDate", Kind.DATE)),
            List.of(
                new Hint(Set.of("identifier"), SearchIndexes.IX_PATIENTS_MRN),
                new Hint(Set.of("phone"), SearchIndexes.IX_PATIENTS_PHONE_BIRTHDATE),
                new Hint(Set.of("name"), SearchIndexes.IX_PATIENTS_NAME_TOKENS_BIRTHDATE),
                new Hint(Set.of("birthdate"), SearchIndexes.IX_PATIENTS_PHONE_BIRTHDATE))));

    types.put(
        "Observation",
        new Collection(
            "Observation",
            SearchIndexes.OBSERVATIONS,
            Map.of(
                "patient", new Field("patientId", Kind.REFERENCE),
                "encounter", new Field("search.encounterId", Kind.REFERENCE),
                "category", new Field("search.category", Kind.TOKEN),
                "code", new Field("search.code", Kind.TOKEN),
                "date", new Field("search.effective", Kind.DATE)),
            List.of(
                new Hint(
                    Set.of("patient"), SearchIndexes.IX_OBSERVATIONS_PATIENT_CATEGORY_EFFECTIVE),
                new Hint(Set.of("encounter"), SearchIndexes.IX_OBSERVATIONS_ENCOUNTER_ID))));

    types.put(
        "DocumentReference",
        new Collection(
            "DocumentReference",
            SearchIndexes.DOCUMENT_REFERENCES,
            Map.of(
                "patient", new Field("patientId", Kind.REFERENCE),
                "encounter", new Field("search.encounterId", Kind.REFERENCE),
                "type", new Field("search.type", Kind.TOKEN),
                "date", new Field("search.date", Kind.DATE),
                // 03 §3 does not normalize the FHIR status; filter the stored resource instead.
                "status", new Field("resource.status", Kind.TOKEN)),
            List.of(
                new Hint(Set.of("patient"), SearchIndexes.IX_DOCUMENT_REFERENCES_PATIENT_DATE),
                new Hint(Set.of("encounter"), SearchIndexes.IX_DOCUMENT_REFERENCES_ENCOUNTER_ID))));

    return new SearchCatalog(types);
  }

  /**
   * The collection for a resource type.
   *
   * @throws IllegalArgumentException when the type is not stored in MongoDB or not searchable here
   */
  public Collection require(String resourceType) {
    Collection collection = byType.get(resourceType);
    if (collection == null) {
      throw new IllegalArgumentException(
          "Mongo search does not support resource type "
              + resourceType
              + "; supported: "
              + byType.keySet());
    }
    return collection;
  }
}
