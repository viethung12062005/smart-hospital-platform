package org.shp.notification.infra;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Exposes the current number of permanently failed reminders.
 *
 * <p>This includes reminders dead-lettered during worker recovery. No patient or contact
 * information is exposed.
 */
@Component
public class ReminderDeadLetterMetrics {

  public ReminderDeadLetterMetrics(MongoTemplate mongoTemplate, MeterRegistry registry) {

    Gauge.builder(
            "shp.notification.deadletter.pending",
            mongoTemplate,
            template ->
                template.count(
                    Query.query(Criteria.where("status").is("dead-lettered")), "reminders"))
        .description("Current number of dead-lettered notification reminders")
        .register(registry);
  }
}
