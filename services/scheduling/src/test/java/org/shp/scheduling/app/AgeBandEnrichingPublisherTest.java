package org.shp.scheduling.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.scheduling.domain.AgeBandClient;

/** SCH-23 (US-015): the relay adds {@code ageBand} to appointment-booked, without blocking it. */
class AgeBandEnrichingPublisherTest {
  private static final UUID PATIENT = UUID.fromString("0192f8a4-0000-7000-8000-000000000001");

  private final List<CloudEvent> published = new ArrayList<>();
  private final AtomicInteger lookups = new AtomicInteger();
  private Function<UUID, Optional<String>> answer;
  private Instant now;
  private AgeBandEnrichingPublisher publisher;

  @BeforeEach
  void setUp() {
    now = Instant.parse("2026-12-01T00:00:00Z");
    answer = id -> Optional.of("40-49");
    AgeBandClient client =
        id -> {
          lookups.incrementAndGet();
          return answer.apply(id);
        };
    Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now;
          }
        };
    publisher = new AgeBandEnrichingPublisher(published::add, client, clock);
  }

  private CloudEvent event(String type) {
    return CloudEvents.create(
        "scheduling",
        type,
        "Appointment/1",
        Map.of("appointmentId", "a1", "patientId", PATIENT.toString()),
        Clock.fixed(now, ZoneOffset.UTC),
        null);
  }

  private String band(int index) {
    return published.get(index).data().path("ageBand").asString();
  }

  @Test
  @DisplayName("appointment-booked gets the band from clinical")
  void addsTheBand() {
    publisher.publish(event(AgeBandEnrichingPublisher.BOOKED));

    assertThat(band(0)).isEqualTo("40-49");
    assertThat(published.get(0).data().path("appointmentId").asString()).isEqualTo("a1");
  }

  @Test
  @DisplayName("the band is cached for 24 hours, then looked up again")
  void cachesForADay() {
    publisher.publish(event(AgeBandEnrichingPublisher.BOOKED));
    publisher.publish(event(AgeBandEnrichingPublisher.BOOKED));
    assertThat(lookups).hasValue(1);

    now = now.plus(Duration.ofHours(24)).plusSeconds(1);
    answer = id -> Optional.of("50-59");
    publisher.publish(event(AgeBandEnrichingPublisher.BOOKED));

    assertThat(lookups).hasValue(2);
    assertThat(band(2)).isEqualTo("50-59");
  }

  @Test
  @DisplayName("a clinical error does not block publishing: ageBand is unknown and not cached")
  void clinicalErrorGivesUnknown() {
    answer =
        id -> {
          throw new IllegalStateException("clinical down");
        };
    publisher.publish(event(AgeBandEnrichingPublisher.BOOKED));
    assertThat(published).hasSize(1);
    assertThat(band(0)).isEqualTo("unknown");

    answer = id -> Optional.of("30-39");
    publisher.publish(event(AgeBandEnrichingPublisher.BOOKED));
    assertThat(band(1)).isEqualTo("30-39");
  }

  @Test
  @DisplayName("an unknown patient gives ageBand unknown")
  void unknownPatient() {
    answer = id -> Optional.empty();
    publisher.publish(event(AgeBandEnrichingPublisher.BOOKED));
    assertThat(band(0)).isEqualTo("unknown");
  }

  @Test
  @DisplayName("other events pass through untouched and cause no lookup")
  void otherEventsAreUntouched() {
    CloudEvent cancelled = event("shp.scheduling.appointment-cancelled.v1");
    publisher.publish(cancelled);

    assertThat(published).containsExactly(cancelled);
    assertThat(lookups).hasValue(0);
  }
}
