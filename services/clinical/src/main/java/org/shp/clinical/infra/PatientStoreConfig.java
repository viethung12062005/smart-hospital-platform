package org.shp.clinical.infra;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.shp.data.mongo.MongoMessaging;
import org.shp.data.mongo.MongoOutbox;
import org.shp.data.mongo.MongoResourceStore;
import org.shp.data.mongo.search.SearchIndexes;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the resource store and creates the collections and the patient indexes of 03 §2.2
 * (idempotent). Transactions do not create collections, so they must exist before the first write.
 */
@Configuration
class PatientStoreConfig {
  static final String PATIENTS = "patients";

  @Bean
  MongoResourceStore resourceStore(
      MongoTemplate mongo, TransactionTemplate tx, MongoOutbox outbox, Clock clock) {
    return new MongoResourceStore(mongo, tx, outbox, clock);
  }

  @Bean
  ApplicationRunner patientCollections(MongoTemplate mongo) {
    return args -> ensurePatientIndexes(mongo);
  }

  static void ensurePatientIndexes(MongoTemplate mongo) {
    for (String c :
        new String[] {
          PATIENTS,
          MrnGenerator.COLLECTION,
          MongoMessaging.RESOURCE_HISTORY,
          IdentityLinkRepository.COLLECTION
        }) {
      if (!mongo.collectionExists(c)) {
        mongo.createCollection(c);
      }
    }
    MongoMessaging.ensureIndexes(mongo, MongoMessaging.Store.RESOURCE_HISTORY);
    // An explicit name keeps the index stable across deployments: MongoDB rejects an unnamed index
    // on the same key with IndexOptionsConflict when the collection already holds it under another
    // name, which makes the service fail at startup (seen on the local cluster).
    createIndex(
        mongo.indexOps(IdentityLinkRepository.COLLECTION),
        new Index().on("fhirUser", Sort.Direction.ASC).unique().named("ux_fhir_user"));
    // The patient indexes carry the names the search executor (SHR-11) hints; with the default
    // names every C6 search fails with "hint provided does not correspond to an existing index".
    IndexOperations indexes = mongo.indexOps(PATIENTS);
    createIndex(
        indexes,
        new Index()
            .on("search.mrn", Sort.Direction.ASC)
            .unique()
            .named(SearchIndexes.IX_PATIENTS_MRN));
    createIndex(
        indexes,
        new Index()
            .on("search.phone", Sort.Direction.ASC)
            .on("search.birthDate", Sort.Direction.ASC)
            .named(SearchIndexes.IX_PATIENTS_PHONE_BIRTHDATE));
    createIndex(
        indexes,
        new Index()
            .on("search.nameTokens", Sort.Direction.ASC)
            .on("search.birthDate", Sort.Direction.ASC)
            .named(SearchIndexes.IX_PATIENTS_NAME_TOKENS_BIRTHDATE));
    // CLN-30: one record per CCCD (03 §2.2), only where a number is stored.
    createIndex(
        indexes,
        new Index()
            .on("search.nationalIdHash", Sort.Direction.ASC)
            .unique()
            .partial(
                org.springframework.data.mongodb.core.index.PartialIndexFilter.of(
                    org.springframework.data.mongodb.core.query.Criteria.where(
                            "search.nationalIdHash")
                        .exists(true)))
            .named(SearchIndexes.IX_PATIENTS_NATIONAL_ID_HASH));
  }

  /**
   * Creates a named index, first dropping an existing index on the same keys that carries another
   * name. MongoDB refuses the same keys under a second name (error 85), so a database created by
   * CLN-03..05 — which left MongoDB to name the indexes {@code search.mrn_1}, ... — would otherwise
   * stop the new build from starting (seen on the local cluster). Dropping and recreating keeps the
   * keys and the options that matter (unique, order) and costs one index build.
   */
  private static void createIndex(IndexOperations indexes, Index index) {
    String name = index.getIndexOptions().getString("name");
    if (name == null) {
      throw new IllegalArgumentException("Every index must be named explicitly.");
    }
    Document keys = index.getIndexKeys();
    for (IndexInfo existing : indexes.getIndexInfo()) {
      if (!name.equals(existing.getName())
          && keysOf(existing).equals(new ArrayList<>(keys.keySet()))) {
        indexes.dropIndex(existing.getName());
      }
    }
    indexes.createIndex(index);
  }

  private static List<String> keysOf(IndexInfo index) {
    return index.getIndexFields().stream().map(field -> field.getKey()).toList();
  }
}
