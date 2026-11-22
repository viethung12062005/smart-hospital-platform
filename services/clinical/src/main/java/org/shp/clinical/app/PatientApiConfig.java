package org.shp.clinical.app;

import java.time.Clock;
import org.shp.clinical.infra.PatientRepository;
import org.shp.events.audit.AuditSink;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/** The two fact resolvers named by {@code @RequireShpAuthz(context = ...)} on the Patient API. */
@Configuration
class PatientApiConfig {
  @Bean("patientFacts")
  PatientFacts patientFacts(
      PatientRepository patients, MongoTemplate mongo, AuditSink audit, Clock clock) {
    return new PatientFacts(patients, mongo, audit, clock, false);
  }

  /** C7 is for nurses only: the matrix has no history action, so doctor facts are withheld. */
  @Bean("patientHistoryFacts")
  PatientFacts patientHistoryFacts(
      PatientRepository patients, MongoTemplate mongo, AuditSink audit, Clock clock) {
    return new PatientFacts(patients, mongo, audit, clock, true);
  }
}
