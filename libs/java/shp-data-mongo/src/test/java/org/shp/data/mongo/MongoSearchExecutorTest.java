package org.shp.data.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.data.mongo.search.MongoSearchExecutor;
import org.shp.data.mongo.search.SearchCursor;
import org.shp.data.mongo.search.SearchIndexes;
import org.shp.data.mongo.search.SearchPage;
import org.shp.data.mongo.search.SearchPlan;
import org.shp.fhir.search.Criterion;
import org.shp.fhir.search.SearchParser;
import org.shp.fhir.search.SearchQuery;

/**
 * The executor runs against a real MongoDB 8 replica set (Testcontainers). The explain assertions
 * are the task's "Done when": every main lookup must use {@code IXSCAN}, which the executor makes
 * deterministic by hinting the index from {@link SearchIndexes}.
 */
class MongoSearchExecutorTest extends MongoTestBase {
  static final SearchParser PARSER = SearchParser.standard();
  static final MongoSearchExecutor EXECUTOR = new MongoSearchExecutor(DB);

  @BeforeAll
  static void createSearchIndexes() {
    SearchIndexes.ensureIndexes(DB);
  }

  @BeforeEach
  void clearSearchCollections() {
    for (String collection :
        new String[] {
          SearchIndexes.PATIENTS, SearchIndexes.OBSERVATIONS, SearchIndexes.DOCUMENT_REFERENCES
        }) {
      DB.getCollection(collection).deleteMany(new Document());
    }
  }

  // ---------- the indexes themselves ----------

  @Test
  void ensureIndexesIsIdempotentAndCreatesTheSearchIndexes() {
    SearchIndexes.ensureIndexes(DB);

    var patients = DB.indexOps(SearchIndexes.PATIENTS).getIndexInfo();
    assertThat(patients)
        .anyMatch(i -> i.getName().equals(SearchIndexes.IX_PATIENTS_MRN) && i.isUnique());
    assertThat(patients)
        .anyMatch(i ->
            i.getName().equals(SearchIndexes.IX_PATIENTS_NATIONAL_ID_HASH)
                && i.isUnique()
                && i.getPartialFilterExpression() != null);
    assertThat(patients)
        .anyMatch(i -> i.getName().equals(SearchIndexes.IX_PATIENTS_PHONE_BIRTHDATE));
    assertThat(patients)
        .anyMatch(i -> i.getName().equals(SearchIndexes.IX_PATIENTS_NAME_TOKENS_BIRTHDATE));

    assertThat(DB.indexOps(SearchIndexes.OBSERVATIONS).getIndexInfo())
        .anyMatch(
            i -> i.getName().equals(SearchIndexes.IX_OBSERVATIONS_PATIENT_CATEGORY_EFFECTIVE));
    assertThat(DB.indexOps(SearchIndexes.OBSERVATIONS).getIndexInfo())
        .anyMatch(i -> i.getName().equals(SearchIndexes.IX_OBSERVATIONS_ENCOUNTER_ID));

    assertThat(DB.indexOps(SearchIndexes.DOCUMENT_REFERENCES).getIndexInfo())
        .anyMatch(i -> i.getName().equals(SearchIndexes.IX_DOCUMENT_REFERENCES_PATIENT_DATE));
    assertThat(DB.indexOps(SearchIndexes.DOCUMENT_REFERENCES).getIndexInfo())
        .anyMatch(i -> i.getName().equals(SearchIndexes.IX_DOCUMENT_REFERENCES_ENCOUNTER_ID));
  }

  // ---------- Patient ----------

  @Test
  void patientNameSearchIgnoresDiacriticsAndUsesTheNameIndex() {
    patient("p1", "BN-2026-000001", "+84901234567", "1990-05-01T00:00:00Z", "nguyen", "van", "an");
    patient(
        "p2", "BN-2026-000002", "+84901234568", "1985-01-01T00:00:00Z", "nguyen", "van", "binh");
    patient("p3", "BN-2026-000003", "+84901234569", "1992-02-02T00:00:00Z", "tran", "thi", "binh");

    SearchQuery query = query("Patient", Map.of("name", List.of("Nguyễn Văn An")));
    SearchPage page = EXECUTOR.search(query);

    assertThat(ids(page)).containsExactly("p1");
    assertUsesIndex(EXECUTOR.plan(query, null));
  }

  @Test
  void phoneAndBirthdateAreAndedForTheDeduplicationLookup() {
    patient("p1", "BN-2026-000001", "+84901234567", "1990-05-01T00:00:00Z", "nguyen", "van", "an");
    patient("p2", "BN-2026-000002", "+84901234567", "1985-01-01T00:00:00Z", "le", "thi", "hoa");
    patient("p3", "BN-2026-000003", "+84909999999", "1990-05-01T00:00:00Z", "pham", "van", "cuong");

    SearchQuery query =
        query(
            "Patient",
            Map.of(
                "phone", List.of("+84901234567"),
                "birthdate", List.of("1990-05-01")));

    assertThat(ids(EXECUTOR.search(query))).containsExactly("p1");
    assertUsesIndex(EXECUTOR.plan(query, null));
  }

  @Test
  void identifierSearchMatchesOnlyTheMrnSystem() {
    patient("p1", "BN-2026-000001", "+84901234567", "1990-05-01T00:00:00Z", "nguyen", "van", "an");

    SearchQuery hit = query("Patient", Map.of("identifier", List.of("urn:shp:mrn|BN-2026-000001")));
    assertThat(ids(EXECUTOR.search(hit))).containsExactly("p1");
    assertUsesIndex(EXECUTOR.plan(hit, null));

    assertThat(
            EXECUTOR
                .search(
                    query("Patient", Map.of("identifier", List.of("urn:shp:cccd|123456789012"))))
                .items())
        .isEmpty();
    // "system|" means every code of that system; the normalized field cannot express it.
    assertThat(
            EXECUTOR
                .search(query("Patient", Map.of("identifier", List.of("urn:shp:mrn|"))))
                .items())
        .isEmpty();
  }

  // ---------- Observation ----------

  @Test
  void observationPaginationRespectsCountAndKeysetWithoutGapsOrDuplicates() {
    for (int i = 1; i <= 5; i++) {
      observation("obs-" + i, "p1", "e1", "vital-signs", "8867-4", "2026-11-20T02:0" + i + ":00Z");
    }
    observation("obs-6", "p2", "e2", "vital-signs", "8867-4", "2026-11-20T02:10:00Z");

    SearchQuery query =
        query("Observation", Map.of("patient", List.of("Patient/p1"), "_count", List.of("2")));
    assertUsesIndex(EXECUTOR.plan(query, null));

    SearchPage first = EXECUTOR.search(query);
    assertThat(ids(first)).containsExactly("obs-1", "obs-2");
    assertThat(first.next()).isNotNull();

    SearchPage second = EXECUTOR.search(query, first.next());
    assertThat(ids(second)).containsExactly("obs-3", "obs-4");

    SearchPage third = EXECUTOR.search(query, second.next());
    assertThat(ids(third)).containsExactly("obs-5");
    assertThat(third.next()).isNull();

    assertThat(EXECUTOR.search(query, new SearchCursor("zzz")).items()).isEmpty();
  }

  @Test
  void observationEncounterAndDateRangeSearchUsesTheEncounterIndex() {
    observation("obs-a", "p1", "e1", "laboratory", "2345-7", "2026-10-15T00:00:00Z");
    observation("obs-b", "p1", "e1", "laboratory", "2345-7", "2026-11-15T00:00:00Z");
    observation("obs-c", "p1", "e2", "laboratory", "2345-7", "2026-11-15T00:00:00Z");

    SearchQuery query =
        query(
            "Observation",
            Map.of(
                "encounter",
                List.of("Encounter/e1"),
                "date",
                List.of("ge2026-11-01T00:00:00Z", "le2026-11-30T23:59:59Z")));

    assertThat(ids(EXECUTOR.search(query))).containsExactly("obs-b");
    SearchPlan plan = EXECUTOR.plan(query, null);
    assertThat(plan.index()).isEqualTo(SearchIndexes.IX_OBSERVATIONS_ENCOUNTER_ID);
    assertUsesIndex(plan);
  }

  // ---------- DocumentReference ----------

  @Test
  void documentSearchByPatientAndDateRangeUsesThePatientDateIndex() {
    documentRef("d1", "p1", "e1", "11502-2", "2026-11-10T00:00:00Z", "current");
    documentRef("d2", "p1", "e1", "18748-4", "2026-10-01T00:00:00Z", "current");
    documentRef("d3", "p2", "e2", "11502-2", "2026-11-10T00:00:00Z", "current");

    SearchQuery query =
        query(
            "DocumentReference",
            Map.of(
                "patient", List.of("Patient/p1"),
                "date", List.of("ge2026-11-01T00:00:00Z")));

    assertThat(ids(EXECUTOR.search(query))).containsExactly("d1");
    assertUsesIndex(EXECUTOR.plan(query, null));
  }

  @Test
  void documentTypeAndStatusCriteriaOrTheirValuesAndStatusFiltersTheStoredResource() {
    documentRef("d1", "p1", "e1", "11502-2", "2026-11-10T00:00:00Z", "current");
    documentRef("d2", "p1", "e1", "18748-4", "2026-11-11T00:00:00Z", "current");
    documentRef("d3", "p1", "e1", "11502-2", "2026-11-12T00:00:00Z", "superseded");

    assertThat(
            ids(
                EXECUTOR.search(
                    query(
                        "DocumentReference",
                        Map.of(
                            "patient",
                            List.of("Patient/p1"),
                            "type",
                            List.of("11502-2,18748-4"))))))
        .containsExactly("d1", "d2", "d3");

    assertThat(
            ids(
                EXECUTOR.search(
                    query(
                        "DocumentReference",
                        Map.of(
                            "patient", List.of("Patient/p1"),
                            "status", List.of("current"))))))
        .containsExactly("d1", "d2");
  }

  // ---------- refusals ----------

  @Test
  void postgresBackedTypesEmptySearchesAndOpaqueTokensAreRejected() {
    SearchQuery schedule = query("Schedule", Map.of("actor", List.of("Practitioner/p1")));
    assertThatThrownBy(() -> EXECUTOR.search(schedule))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Patient");

    assertThatThrownBy(() -> EXECUTOR.search(new SearchQuery("Patient", List.of(), 20, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one criterion");

    assertThatThrownBy(() -> EXECUTOR.search(new SearchQuery("Patient", List.of(), 0, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("_count");

    SearchQuery withToken =
        new SearchQuery(
            "Patient",
            List.of(
                new Criterion.TokenCriterion(
                    "identifier", List.of(new Criterion.Token("urn:shp:mrn", "BN-2026-000001")))),
            20,
            "opaque-token");
    assertThatThrownBy(() -> EXECUTOR.search(withToken))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SHR-04");
    // The decoded cursor is authoritative; the query's opaque token is ignored by this overload.
    assertThat(EXECUTOR.search(withToken, new SearchCursor("p0")).items()).isEmpty();
  }

  // ---------- helpers ----------

  static SearchQuery query(String resourceType, Map<String, List<String>> params) {
    return PARSER.parse(resourceType, params);
  }

  static void patient(String id, String mrn, String phone, String birthDate, String... nameTokens) {
    DB.insert(
        new Document("_id", id)
            .append("versionId", 1)
            .append(
                "search",
                new Document("mrn", mrn)
                    .append("phone", phone)
                    .append("birthDate", date(birthDate))
                    .append("nameTokens", List.of(nameTokens))),
        SearchIndexes.PATIENTS);
  }

  static void observation(
      String id,
      String patientId,
      String encounterId,
      String category,
      String code,
      String effective) {
    DB.insert(
        new Document("_id", id)
            .append("patientId", patientId)
            .append(
                "search",
                new Document("encounterId", encounterId)
                    .append("category", category)
                    .append("code", code)
                    .append("effective", date(effective))),
        SearchIndexes.OBSERVATIONS);
  }

  static void documentRef(
      String id, String patientId, String encounterId, String type, String when, String status) {
    DB.insert(
        new Document("_id", id)
            .append("patientId", patientId)
            .append(
                "search",
                new Document("encounterId", encounterId)
                    .append("type", type)
                    .append("date", date(when)))
            .append(
                "resource",
                new Document("resourceType", "DocumentReference").append("status", status)),
        SearchIndexes.DOCUMENT_REFERENCES);
  }

  static Date date(String instant) {
    return Date.from(Instant.parse(instant));
  }

  static List<String> ids(SearchPage page) {
    return page.items().stream().map(d -> d.getString("_id")).toList();
  }

  static void assertUsesIndex(SearchPlan plan) {
    assertThat(plan.index()).as("hinted index").isNotNull();
    assertThat(stages(plan))
        .as("explain stages of %s", plan.index())
        .anyMatch(s -> s.contains("IXSCAN"));
  }

  static List<String> stages(SearchPlan plan) {
    Document command =
        new Document(
                "explain",
                new Document("find", plan.collection())
                    .append("filter", plan.query().getQueryObject())
                    .append("sort", plan.query().getSortObject())
                    .append("hint", plan.index())
                    .append("limit", plan.query().getLimit()))
            .append("verbosity", "executionStats");
    List<String> stages = new ArrayList<>();
    collectStages(DB.getDb().runCommand(command), stages);
    return stages;
  }

  static void collectStages(Object node, List<String> stages) {
    if (node instanceof Document document) {
      Object stage = document.get("stage");
      if (stage instanceof String name) {
        stages.add(name);
      }
      document.values().forEach(value -> collectStages(value, stages));
    } else if (node instanceof List<?> list) {
      list.forEach(value -> collectStages(value, stages));
    }
  }
}
