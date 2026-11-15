package org.shp.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.springframework.messaging.Message;

/**
 * Build consumers for Spring Cloud Stream per 13-events §1.1: parse + validate the schema, skip
 * unregistered types, process exactly once through the inbox. Handler throws exception → binder
 * does not ack → redelivery, more than 5 times → DLQ.
 *
 * <pre>{@code
 * @Bean
 * Consumer<Message<byte[]>> appointmentEvents(PostgresInbox inbox, ReminderService reminders) {
 *   return EventConsumers.idempotent("notification", Set.of("shp.scheduling.appointment-booked.v1"),
 *       inbox::handleOnce, reminders::onBooked);
 * }
 * }</pre>
 */
public final class EventConsumers {
  private EventConsumers() {}

  public static Consumer<Message<byte[]>> idempotent(
      String consumer, Set<String> types, Inbox inbox, Consumer<CloudEvent> handler) {
    Set<String> accepted = Set.copyOf(types);
    return message -> {
      CloudEvent event = parse(message.getPayload());
      if (event.id() == null || event.type() == null) {
        throw new PoisonEventException(event.id(), event.type(), List.of("envelope"));
      }
      if (!accepted.contains(event.type())) {
        return; // not this consumer's job
      }
      List<String> errors = EventSchemas.validate(event);
      if (!errors.isEmpty()) {
        throw new PoisonEventException(event.id(), event.type(), locations(errors));
      }
      inbox.handleOnce(consumer, event, () -> handler.accept(event));
    };
  }

  private static CloudEvent parse(byte[] payload) {
    try {
      return CloudEvents.parse(new String(payload, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new PoisonEventException(e);
    }
  }

  /** "data /patientId: ..." → "data /patientId": drop the message part that may contain values. */
  private static List<String> locations(List<String> errors) {
    return errors.stream()
        .map(e -> e.contains(":") ? e.substring(0, e.indexOf(':')) : e)
        .distinct()
        .toList();
  }
}
