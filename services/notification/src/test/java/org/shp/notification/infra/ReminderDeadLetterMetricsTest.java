package org.shp.notification.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class ReminderDeadLetterMetricsTest {

  @Test
  void gaugeReportsCurrentDeadLetterCount() {
    MongoTemplate mongo = mock(MongoTemplate.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    when(mongo.count(any(Query.class), eq("reminders"))).thenReturn(2L).thenReturn(0L);

    new ReminderDeadLetterMetrics(mongo, registry);

    Gauge gauge = registry.find("shp.notification.deadletter.pending").gauge();

    org.junit.jupiter.api.Assertions.assertNotNull(gauge);

    assertEquals(2.0, gauge.value());
    assertEquals(0.0, gauge.value());

    registry.close();
  }
}
