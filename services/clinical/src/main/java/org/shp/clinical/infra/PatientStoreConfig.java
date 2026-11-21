package org.shp.clinical.infra;

import java.time.Clock;
import org.shp.data.mongo.MongoMessaging;
import org.shp.data.mongo.MongoOutbox;
import org.shp.data.mongo.MongoResourceStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
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
    mongo
        .indexOps(IdentityLinkRepository.COLLECTION)
        .createIndex(new Index().on("fhirUser", Sort.Direction.ASC).unique().named("ux_fhir_user"));
    var indexes = mongo.indexOps(PATIENTS);
    indexes.createIndex(new Index().on("search.mrn", Sort.Direction.ASC).unique());
    indexes.createIndex(
        new Index()
            .on("search.phone", Sort.Direction.ASC)
            .on("search.birthDate", Sort.Direction.ASC));
    indexes.createIndex(
        new Index()
            .on("search.nameTokens", Sort.Direction.ASC)
            .on("search.birthDate", Sort.Direction.ASC));
  }
}
