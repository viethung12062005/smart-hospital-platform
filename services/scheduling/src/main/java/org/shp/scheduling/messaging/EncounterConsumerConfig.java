package org.shp.scheduling.messaging;

import java.util.Set;
import java.util.function.Consumer;
import org.shp.data.postgres.PostgresInbox;
import org.shp.messaging.EventConsumers;
import org.shp.scheduling.app.AppointmentFulfillmentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

/**
 * E6 {@code encounter-finished} → Appointment {@code fulfilled} (13-events §2), exactly once
 * through the inbox (13-events §1.1). Binding {@code encounterEvents-in-0}, group {@code
 * scheduling}.
 */
@Configuration
class EncounterConsumerConfig {

  @Bean
  Consumer<Message<byte[]>> encounterEvents(
      PostgresInbox inbox, AppointmentFulfillmentService fulfillment) {
    return EventConsumers.idempotent(
        AppointmentFulfillmentService.CONSUMER,
        Set.of(AppointmentFulfillmentService.FINISHED_TYPE),
        inbox::handleOnce,
        fulfillment::onEncounterFinished);
  }
}
