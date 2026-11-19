package org.shp.clinical.messaging;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.bson.Document;
import org.shp.clinical.app.CareRelationshipService;
import org.shp.clinical.app.EncounterIntake;
import org.shp.clinical.infra.MongoCareRelationships;
import org.shp.clinical.infra.MongoEncounters;
import org.shp.data.mongo.MongoInbox;
import org.shp.data.mongo.MongoMessaging;
import org.shp.events.CloudEvent;
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
 * The appointment events clinical consumes (13 §2), exactly once through the inbox (13 §1.1): E1,
 * E2, E3, E5 → care relationships (CLN-13); E4 → the {@code arrived} Encounter (CLN-14). Binding
 * {@code appointmentEvents-in-0}, group {@code clinical}.
 */
@Configuration
class AppointmentConsumerConfig {

  /** Every type this consumer handles; the others on the topic are skipped. */
  static Set<String> types() {
    Set<String> types = new HashSet<>(CareRelationshipService.TYPES);
    types.add(EncounterIntake.CHECKED_IN);
    return Set.copyOf(types);
  }

  @Bean
  Consumer<Message<byte[]>> appointmentEvents(
      MongoInbox inbox, CareRelationshipService care, EncounterIntake encounters) {
    return EventConsumers.idempotent(
        CareRelationshipService.CONSUMER, types(), inbox::handleOnce, dispatch(care, encounters));
  }

  /** Routes an accepted event to its handler. */
  static Consumer<CloudEvent> dispatch(CareRelationshipService care, EncounterIntake encounters) {
    return event -> {
      if (EncounterIntake.CHECKED_IN.equals(event.type())) {
        encounters.onCheckedIn(event);
      } else {
        care.handle(event);
      }
    };
  }

  /**
   * A transaction cannot create a collection: create {@code care_relationships}, {@code encounters}
   * and {@code resource_history} with their 03 §2.2 indexes at startup, idempotently.
   */
  @Bean
  ApplicationRunner appointmentCollections(MongoTemplate mongo) {
    return args -> {
      for (String c :
          new String[] {
            MongoCareRelationships.COLLECTION,
            MongoEncounters.COLLECTION,
            MongoMessaging.RESOURCE_HISTORY
          }) {
        if (!mongo.collectionExists(c)) {
          mongo.createCollection(c);
        }
      }
      MongoMessaging.ensureIndexes(mongo, MongoMessaging.Store.RESOURCE_HISTORY);

      var care = mongo.indexOps(MongoCareRelationships.COLLECTION);
      care.createIndex(new Index().on("patientId", Sort.Direction.ASC).named("ix_care_patient_id"));
      care.createIndex(
          new CompoundIndexDefinition(new Document("practitionerId", 1).append("validUntil", -1))
              .named("ix_care_practitioner_valid_until"));

      var encounters = mongo.indexOps(MongoEncounters.COLLECTION);
      encounters.createIndex(
          new CompoundIndexDefinition(new Document("patientId", 1).append("search.periodStart", -1))
              .named("ix_encounters_patient_period_start"));
      encounters.createIndex(
          new CompoundIndexDefinition(
                  new Document("search.practitionerId", 1).append("search.periodStart", -1))
              .named("ix_encounters_practitioner_period_start"));
      // One Encounter per appointment (03 §2.2): the guard behind the existence check of E4.
      encounters.createIndex(
          new Index()
              .on("search.appointmentId", Sort.Direction.ASC)
              .unique()
              .named("ux_encounters_appointment_id"));
    };
  }
}
