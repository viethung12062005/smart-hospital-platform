package org.shp.clinical.infra;

import org.bson.Document;
import org.shp.data.mongo.MongoMessaging;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Creates the practitioner collections and their indexes at startup (03 §2.2), idempotently: a
 * transaction cannot create a collection, and the staff code must be unique even under concurrent
 * writes. CLN-01 creates the rest of the clinical database.
 */
@Component
class PractitionerCollections implements ApplicationRunner {
  static final String IX_STAFF_CODE = "ux_practitioners_staff_code";
  static final String IX_NAME_TOKENS = "ix_practitioners_name_tokens";
  static final String IX_ROLE_PRACTITIONER = "ix_practitioner_roles_practitioner_id";
  static final String IX_ROLE_DIRECTORY = "ix_practitioner_roles_specialty_code_active";

  private final MongoTemplate mongo;

  PractitionerCollections(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (String c :
        new String[] {
          PractitionerRepository.PRACTITIONERS,
          PractitionerRepository.PRACTITIONER_ROLES,
          MongoMessaging.RESOURCE_HISTORY
        }) {
      if (!mongo.collectionExists(c)) {
        mongo.createCollection(c);
      }
    }
    var practitioners = mongo.indexOps(PractitionerRepository.PRACTITIONERS);
    practitioners.createIndex(
        new Index()
            .on("search.staffCode", org.springframework.data.domain.Sort.Direction.ASC)
            .unique()
            .named(IX_STAFF_CODE));
    practitioners.createIndex(
        new Index()
            .on("search.nameTokens", org.springframework.data.domain.Sort.Direction.ASC)
            .named(IX_NAME_TOKENS));
    var roles = mongo.indexOps(PractitionerRepository.PRACTITIONER_ROLES);
    roles.createIndex(
        new Index()
            .on("search.practitionerId", org.springframework.data.domain.Sort.Direction.ASC)
            .named(IX_ROLE_PRACTITIONER));
    roles.createIndex(
        new CompoundIndexDefinition(
                new Document("search.specialty", 1)
                    .append("search.code", 1)
                    .append("search.active", 1))
            .named(IX_ROLE_DIRECTORY));
  }
}
