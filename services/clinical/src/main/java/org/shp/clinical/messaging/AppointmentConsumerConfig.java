package org.shp.clinical.messaging;

import java.util.function.Consumer;
import org.shp.clinical.app.CareRelationshipService;
import org.shp.clinical.infra.MongoCareRelationships;
import org.shp.data.mongo.MongoInbox;
import org.shp.messaging.EventConsumers;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.messaging.Message;

/**
 * The appointment events clinical consumes (13 §2: E1, E2, E3, E5 → care relationships), exactly
 * once through the inbox (13 §1.1). Binding {@code appointmentEvents-in-0}, group {@code clinical}.
 */
@Configuration
class AppointmentConsumerConfig {

  @Bean
  Consumer<Message<byte[]>> appointmentEvents(MongoInbox inbox, CareRelationshipService care) {
    return EventConsumers.idempotent(
        CareRelationshipService.CONSUMER,
        CareRelationshipService.TYPES,
        inbox::handleOnce,
        care::handle);
  }

  /**
   * A transaction cannot create a collection: create {@code care_relationships} and its 03 §2.2
   * indexes at startup, idempotently.
   */
  @Bean
  ApplicationRunner careRelationshipCollection(MongoTemplate mongo) {
    return args -> {
      String c = MongoCareRelationships.COLLECTION;
      if (!mongo.collectionExists(c)) {
        mongo.createCollection(c);
      }
      mongo
          .indexOps(c)
          .createIndex(new Index().on("patientId", Sort.Direction.ASC).named("ix_care_patient_id"));
      mongo
          .indexOps(c)
          .createIndex(
              new CompoundIndexDefinition(
                      new org.bson.Document("practitionerId", 1).append("validUntil", -1))
                  .named("ix_care_practitioner_valid_until"));
    };
  }
}
