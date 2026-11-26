package org.shp.scheduling.messaging;

import java.util.Set;
import java.util.function.Consumer;
import org.shp.data.postgres.PostgresInbox;
import org.shp.messaging.EventConsumers;
import org.shp.scheduling.app.PatientMergeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

/**
 * Scheduling inbound event consumers (13 §1.1, §2): consumes clinical {@code
 * shp.clinical.patient-merged.v1} (E7) idempotently via {@link PostgresInbox}. Binding {@code
 * clinicalEvents-in-0}, group {@code scheduling}.
 */
@Configuration
class SchedulingConsumerConfig {

  @Bean
  Consumer<Message<byte[]>> clinicalEvents(PostgresInbox inbox, PatientMergeService patientMerge) {
    return EventConsumers.idempotent(
        PatientMergeService.CONSUMER,
        Set.of(PatientMergeService.PATIENT_MERGED_TYPE),
        inbox::handleOnce,
        patientMerge::onPatientMerged);
  }
}
