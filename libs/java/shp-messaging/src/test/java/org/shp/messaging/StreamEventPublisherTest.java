package org.shp.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.spring.messaging.servicebus.support.ServiceBusMessageHeaders;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;

class StreamEventPublisherTest {
  @Test
  void headersCarryTypeIdAndSubjectForFilteringDedupAndOrdering() {
    CloudEvent evt =
        CloudEvents.create(
            "scheduling", "shp.scheduling.appointment-booked.v1", "Appointment/a1", Map.of());

    var msg = StreamEventPublisher.toMessage(evt);

    assertThat(msg.getHeaders().get(StreamEventPublisher.TYPE_HEADER)).isEqualTo(evt.type());
    assertThat(msg.getHeaders().get(ServiceBusMessageHeaders.MESSAGE_ID)).isEqualTo(evt.id());
    assertThat(msg.getHeaders().get(ServiceBusMessageHeaders.SESSION_ID))
        .isEqualTo("Appointment/a1");
    assertThat(CloudEvents.parse(new String(msg.getPayload())).id()).isEqualTo(evt.id());
  }

  @Test
  void eventWithoutSubjectUsesItsIdAsSession() {
    CloudEvent evt =
        CloudEvents.create("scheduling", "shp.scheduling.appointment-booked.v1", null, Map.of());

    assertThat(
            StreamEventPublisher.toMessage(evt)
                .getHeaders()
                .get(ServiceBusMessageHeaders.SESSION_ID))
        .isEqualTo(evt.id());
  }
}
