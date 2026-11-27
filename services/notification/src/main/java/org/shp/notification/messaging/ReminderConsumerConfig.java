package org.shp.notification.messaging;

import java.time.Clock;
import java.util.Set;
import java.util.function.Consumer;
import org.shp.data.mongo.MongoInbox;
import org.shp.messaging.EventConsumers;
import org.shp.notification.app.ReminderService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.messaging.Message;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reminder event consumers (04-db-notification-ml §1.3, 13-events §1.1): E1 creates the booking
 * reminders; E10 adds the high-risk t-6h reminder; E9 adds the generic result-available reminder;
 * E7 re-points the patient id. All are processed exactly once through the inbox.
 */
@Configuration
class ReminderConsumerConfig {

  @Bean
  ReminderService reminders(MongoTemplate mongo, TransactionTemplate tx, Clock clock) {
    return new ReminderService(mongo, tx, clock);
  }

  @Bean
  Consumer<Message<byte[]>> appointmentEvents(MongoInbox inbox, ReminderService reminders) {
    return EventConsumers.idempotent(
        ReminderService.CONSUMER,
        Set.of(
            ReminderService.BOOKED_TYPE,
            ReminderService.NO_SHOW_SCORED_TYPE,
            ReminderService.DOCUMENT_RELEASED_TYPE,
            ReminderService.PATIENT_MERGED_TYPE),
        inbox::handleOnce,
        event -> {
          if (ReminderService.BOOKED_TYPE.equals(event.type())) {
            reminders.onBooked(event);
          } else if (ReminderService.NO_SHOW_SCORED_TYPE.equals(event.type())) {
            reminders.onNoShowScored(event);
          } else if (ReminderService.DOCUMENT_RELEASED_TYPE.equals(event.type())) {
            reminders.onDocumentReleased(event);
          } else if (ReminderService.PATIENT_MERGED_TYPE.equals(event.type())) {
            reminders.onPatientMerged(event);
          }
        });
  }

  /**
   * Transactions do not create collections: create the reminders collection and its indexes
   * (idempotent) at startup, matching contracts/db/mongo-notification-ml.js.
   */
  @Bean
  ApplicationRunner reminderCollections(MongoTemplate mongo) {
    return args -> {
      if (!mongo.collectionExists(ReminderService.COLLECTION)) {
        mongo.createCollection(ReminderService.COLLECTION);
      }
      mongo
          .indexOps(ReminderService.COLLECTION)
          .createIndex(
              new Index()
                  .on("subjectRef", Sort.Direction.ASC)
                  .on("kind", Sort.Direction.ASC)
                  .unique()
                  .named("ux_reminder_once"));
      mongo
          .indexOps(ReminderService.COLLECTION)
          .createIndex(
              new Index()
                  .on("dueAt", Sort.Direction.ASC)
                  .named("ix_reminder_due")
                  .partial(PartialIndexFilter.of(Criteria.where("status").is("scheduled"))));
    };
  }
}
