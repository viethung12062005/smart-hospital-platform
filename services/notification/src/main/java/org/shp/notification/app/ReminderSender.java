package org.shp.notification.app;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.bson.Document;
import org.shp.notification.infra.MongoReminderWorkQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Handles one already-claimed reminder.
 *
 * <p>Contact information and message content are transient and are never stored in the reminder
 * document.
 */
@Service
public class ReminderSender {

  private static final Set<String> PERMANENT_ERRORS =
      Set.of("invalid-number", "invalid-recipient", "unregistered-token");

  private final MongoReminderWorkQueue queue;
  private final Map<String, NotificationChannel> channels;
  private final MeterRegistry metrics;
  private final Clock clock;

  @Autowired
  public ReminderSender(
      MongoReminderWorkQueue queue,
      ObjectProvider<NotificationChannel> providers,
      MeterRegistry metrics) {
    this(queue, providers.orderedStream().toList(), metrics, Clock.systemUTC());
  }

  public ReminderSender(
      MongoReminderWorkQueue queue,
      List<NotificationChannel> channelAdapters,
      MeterRegistry metrics,
      Clock clock) {

    this.queue = queue;
    this.metrics = metrics;
    this.clock = clock;

    Map<String, NotificationChannel> registered = new HashMap<>();

    for (NotificationChannel channel : channelAdapters) {
      if (registered.putIfAbsent(channel.channelName(), channel) != null) {
        throw new IllegalArgumentException(
            "Duplicate notification channel: " + channel.channelName());
      }
    }

    this.channels = Map.copyOf(registered);
  }

  /**
   * Sends through priority-ordered channels.
   *
   * <p>messageForChannel resolves a temporary message for that particular channel. Its
   * recipient/content must not be written to MongoDB or logs.
   *
   * @return true when the result was persisted by the current lock owner
   */
  public boolean sendClaimed(
      Document claimed,
      List<String> priority,
      Function<String, NotificationMessage> messageForChannel) {

    if (claimed == null || !"sending".equals(claimed.getString("status"))) {
      throw new IllegalArgumentException("Reminder must already be claimed");
    }

    int attempt = ((Number) claimed.get("attempts")).intValue();

    if (attempt < 1 || attempt > 5) {
      throw new IllegalArgumentException("Invalid attempt number");
    }

    if (priority == null
        || priority.isEmpty()
        || new HashSet<>(priority).size() != priority.size()) {
      throw new IllegalArgumentException("Invalid channel priority");
    }

    List<Document> deliveries = new ArrayList<>();
    boolean sent = false;
    boolean retryableFailure = false;

    for (String channelName : priority) {
      NotificationChannel channel = channels.get(channelName);
      NotificationResult result;

      long started = System.nanoTime();

      if (channel == null) {
        result = NotificationResult.failure("channel-unavailable");
      } else {
        // Message preparation errors are not provider failures.
        NotificationMessage message = messageForChannel.apply(channelName);

        try {
          result = channel.send(message);
          if (result == null) {
            result = NotificationResult.failure("provider-error");
          }
        } catch (RuntimeException exception) {
          // Provider exception messages may contain PII: do not log or persist them.
          result = NotificationResult.failure("provider-error");
        }
      }

      long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);

      Instant attemptedAt = clock.instant();

      deliveries.add(
          new Document("attemptNo", attempt)
              .append("channel", channelName)
              .append("provider", channelName)
              .append("outcome", result.successful() ? "accepted" : "failed")
              .append("providerMessageId", result.providerMessageId())
              .append("errorCode", result.errorCode())
              .append("latencyMs", latencyMs)
              .append("attemptedAt", java.util.Date.from(attemptedAt)));

      if (result.successful()) {
        sent = true;
        break;
      }

      if (!PERMANENT_ERRORS.contains(result.errorCode())) {
        retryableFailure = true;
      }
    }

    boolean permanentFailure = !sent && !retryableFailure;

    boolean persisted =
        queue.finishAttempt(claimed, deliveries, clock.instant(), sent, permanentFailure);

    if (persisted) {
      for (Document delivery : deliveries) {
        metrics
            .counter(
                "shp.notification.delivery.attempts",
                "channel",
                delivery.getString("channel"),
                "outcome",
                delivery.getString("outcome"))
            .increment();
      }

      if (!sent && (attempt == 5 || permanentFailure)) {
        metrics.counter("shp.notification.deadletter.total").increment();
      }

      if (sent) {
        metrics.counter("shp.notification.sent.total").increment();
      }
    }

    return persisted;
  }
}
