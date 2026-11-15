package org.shp.data.mongo.search;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Collection names and the indexes that serve search, transcribed from 03-db-clinical-document §2.2
 * and §3. The indexes are named because {@link MongoSearchExecutor} hints the index that a query is
 * expected to use; a hint keeps the plan stable when statistics are cold or the data is skewed.
 *
 * <p>A service calls {@link #ensureIndexes(MongoTemplate)} at bootstrap or from its init job. The
 * index definitions are deliberately limited to the collections the search executor queries; CLN-01
 * and DOC-01 still create the remaining indexes of their databases.
 */
public final class SearchIndexes {
  public static final String PATIENTS = "patients";
  public static final String OBSERVATIONS = "observations";
  public static final String DOCUMENT_REFERENCES = "document_references";

  public static final String IX_PATIENTS_MRN = "ix_patients_mrn";
  public static final String IX_PATIENTS_PHONE_BIRTHDATE = "ix_patients_phone_birthdate";
  public static final String IX_PATIENTS_NATIONAL_ID_HASH = "ix_patients_national_id_hash";
  public static final String IX_PATIENTS_NAME_TOKENS_BIRTHDATE =
      "ix_patients_name_tokens_birthdate";
  public static final String IX_OBSERVATIONS_PATIENT_CATEGORY_EFFECTIVE =
      "ix_observations_patient_category_effective";
  public static final String IX_OBSERVATIONS_ENCOUNTER_ID = "ix_observations_encounter_id";
  public static final String IX_DOCUMENT_REFERENCES_PATIENT_DATE =
      "ix_document_references_patient_date";
  public static final String IX_DOCUMENT_REFERENCES_ENCOUNTER_ID =
      "ix_document_references_encounter_id";
  public static final String IX_DOCUMENT_REFERENCES_STATE_UPDATED =
      "ix_document_references_state_updated";

  private SearchIndexes() {}

  /** Create every search index (idempotent). */
  public static void ensureIndexes(MongoTemplate mongo) {
    mongo
        .indexOps(PATIENTS)
        .createIndex(
            new Index().on("search.mrn", Sort.Direction.ASC).named(IX_PATIENTS_MRN).unique());
    mongo
        .indexOps(PATIENTS)
        .createIndex(
            new Index()
                .on("search.phone", Sort.Direction.ASC)
                .on("search.birthDate", Sort.Direction.ASC)
                .named(IX_PATIENTS_PHONE_BIRTHDATE));
    mongo
        .indexOps(PATIENTS)
        .createIndex(
            new Index()
                .on("search.nationalIdHash", Sort.Direction.ASC)
                .named(IX_PATIENTS_NATIONAL_ID_HASH)
                .unique()
                .partial(
                    PartialIndexFilter.of(Criteria.where("search.nationalIdHash").exists(true))));
    mongo
        .indexOps(PATIENTS)
        .createIndex(
            new Index()
                .on("search.nameTokens", Sort.Direction.ASC)
                .on("search.birthDate", Sort.Direction.ASC)
                .named(IX_PATIENTS_NAME_TOKENS_BIRTHDATE));

    mongo
        .indexOps(OBSERVATIONS)
        .createIndex(
            new Index()
                .on("patientId", Sort.Direction.ASC)
                .on("search.category", Sort.Direction.ASC)
                .on("search.effective", Sort.Direction.DESC)
                .named(IX_OBSERVATIONS_PATIENT_CATEGORY_EFFECTIVE));
    mongo
        .indexOps(OBSERVATIONS)
        .createIndex(
            new Index()
                .on("search.encounterId", Sort.Direction.ASC)
                .named(IX_OBSERVATIONS_ENCOUNTER_ID));

    mongo
        .indexOps(DOCUMENT_REFERENCES)
        .createIndex(
            new Index()
                .on("patientId", Sort.Direction.ASC)
                .on("search.date", Sort.Direction.DESC)
                .named(IX_DOCUMENT_REFERENCES_PATIENT_DATE));
    mongo
        .indexOps(DOCUMENT_REFERENCES)
        .createIndex(
            new Index()
                .on("search.encounterId", Sort.Direction.ASC)
                .named(IX_DOCUMENT_REFERENCES_ENCOUNTER_ID));
    mongo
        .indexOps(DOCUMENT_REFERENCES)
        .createIndex(
            new Index()
                .on("processing.state", Sort.Direction.ASC)
                .on("lastUpdated", Sort.Direction.ASC)
                .named(IX_DOCUMENT_REFERENCES_STATE_UPDATED));
  }
}
