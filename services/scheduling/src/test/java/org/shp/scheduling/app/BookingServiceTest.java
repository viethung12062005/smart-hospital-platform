package org.shp.scheduling.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.domain.BookingCommand;
import org.shp.scheduling.domain.BookingResult;
import org.shp.scheduling.domain.SlotUnavailableException;
import org.shp.web.errors.ShpException;
import org.shp.web.idempotency.IdempotencyKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-13 (US-015): {@code $book} step 2 on real PostgreSQL 16 - slot compare-and-set, BR-04,
 * appointment, history and the {@code appointment-booked} outbox event in one transaction
 * (02-db-scheduling §3.1, steps 4-6).
 */
@SpringBootTest(
    properties = {
      "shp.auth.authority=https://idp.test/realms/shp",
      "shp.auth.audience=shp-api",
      "shp.events.validate-schema=true"
    })
@Testcontainers
class BookingServiceTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  @Container @ServiceConnection
  static final RabbitMQContainer BUS =
      new RabbitMQContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_RABBITMQ_IMAGE", "rabbitmq:4-management"))
              .asCompatibleSubstituteFor("rabbitmq"));

  @Autowired BookingService service;
  @Autowired JdbcClient jdbc;

  private BookingTestSupport data;
  private UUID doctor;
  private UUID schedule;
  private int counter;

  @BeforeEach
  void seed() {
    data = new BookingTestSupport(jdbc);
    data.clean();
    doctor = UUID.randomUUID();
    schedule = data.schedule(doctor, "cardiology");
    counter = 0;
  }

  private Instant in(long days) {
    return Instant.now()
        .plus(days, ChronoUnit.DAYS)
        .truncatedTo(ChronoUnit.SECONDS)
        .plusSeconds(900L * ++counter);
  }

  private UUID slot(String status, Instant start) {
    return data.slot(schedule, doctor, "cardiology", status, start);
  }

  private static BookingCommand command(UUID slot, UUID patient, String key) {
    byte[] hash =
        IdempotencyKeys.hashBody(("{\"slot\":\"" + slot + "\"}").getBytes(StandardCharsets.UTF_8));
    return new BookingCommand("sub-" + patient, key, hash, slot, patient, "web", "Check-up");
  }

  private int count(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
  }

  private static String code(Throwable t) {
    return ((ShpException) t).info().code();
  }

  @Test
  @DisplayName("books a free slot: slot busy, appointment booked, history v1 and one event")
  void booksAFreeSlot() {
    UUID patient = UUID.randomUUID();
    UUID slot = slot("free", in(3));

    BookingResult result = service.book(command(slot, patient, "key-00000001"));

    assertThat(result.replayed()).isFalse();
    var a = result.appointment();
    assertThat(a.status()).isEqualTo("booked");
    assertThat(a.patientId()).isEqualTo(patient);
    assertThat(a.practitionerId()).isEqualTo(doctor);
    assertThat(a.specialty()).isEqualTo("cardiology");
    assertThat(a.channel()).isEqualTo("web");
    assertThat(
            jdbc.sql(
                    "SELECT status || ':' || version_id || ':' || (content->>'status') FROM scheduling.slot WHERE id = ?")
                .param(slot)
                .query(String.class)
                .single())
        .isEqualTo("busy:2:busy");
    var fhir = FhirJson.parse(a.content(), org.hl7.fhir.r4.model.Appointment.class);
    assertThat(fhir.getIdElement().getIdPart()).isEqualTo(a.id().toString());
    assertThat(fhir.getSlotFirstRep().getReference()).isEqualTo("Slot/" + slot);
    assertThat(fhir.getComment()).isEqualTo("Check-up");
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.resource_history WHERE resource_type = 'Appointment' AND version_id = 1 AND operation = 'create'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("the commit includes exactly one schema-valid appointment-booked event, no PII")
  void commitIncludesTheOutboxEvent() {
    UUID patient = UUID.randomUUID();
    UUID slot = slot("free", in(2));
    var a = service.book(command(slot, patient, "key-00000002")).appointment();

    String payload =
        jdbc.sql("SELECT payload::text FROM outbox WHERE event_type = ?")
            .param(BookingService.EVENT_TYPE)
            .query(String.class)
            .single();
    CloudEvent evt = CloudEvents.parse(payload);
    assertThat(EventSchemas.validate(evt)).isEmpty();
    assertThat(evt.subject()).isEqualTo("Appointment/" + a.id());
    assertThat(evt.data().get("appointmentId").asString()).isEqualTo(a.id().toString());
    assertThat(evt.data().get("patientId").asString()).isEqualTo(patient.toString());
    Set<String> fields = new java.util.TreeSet<>();
    evt.data().propertyNames().forEach(fields::add);
    assertThat(fields)
        .containsExactlyInAnyOrder(
            "appointmentId",
            "version",
            "patientId",
            "practitionerId",
            "slotId",
            "specialty",
            "start",
            "end",
            "channel",
            "bookedAt");
  }

  @Test
  @DisplayName("a booked slot is 409 slot-unavailable and nothing changes")
  void bookedSlotIsAConflict() {
    UUID slot = slot("free", in(3));
    service.book(command(slot, UUID.randomUUID(), "key-00000003"));

    assertThatThrownBy(() -> service.book(command(slot, UUID.randomUUID(), "key-00000004")))
        .isInstanceOf(SlotUnavailableException.class)
        .satisfies(e -> {
          assertThat(((ShpException) e).info().status()).isEqualTo(409);
          assertThat(((SlotUnavailableException) e).practitionerId()).isEqualTo(doctor);
          assertThat(((SlotUnavailableException) e).specialty()).isEqualTo("cardiology");
        });
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM idempotency_key WHERE key = 'key-00000004'")).isZero();
  }

  @Test
  @DisplayName("a blocked slot and a slot in the past are 409 slot-unavailable")
  void blockedAndPastSlotsAreConflicts() {
    UUID blocked = slot("busy-unavailable", in(3));
    UUID past = slot("free", Instant.now().minus(1, ChronoUnit.DAYS));
    for (UUID s : List.of(blocked, past)) {
      assertThatThrownBy(() ->
              service.book(command(s, UUID.randomUUID(), "key-" + s.toString().substring(0, 8))))
          .isInstanceOf(SlotUnavailableException.class);
    }
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isZero();
  }

  @Test
  @DisplayName("a slot beyond 30 days is 422 outside-booking-window; one inside the window books")
  void bookingWindowIs30Days() {
    UUID far = slot("free", in(31));
    assertThatThrownBy(() -> service.book(command(far, UUID.randomUUID(), "key-00000005")))
        .isInstanceOf(ShpException.class)
        .satisfies(e -> {
          assertThat(code(e)).isEqualTo("outside-booking-window");
          assertThat(((ShpException) e).info().status()).isEqualTo(422);
        });
    UUID near = slot("free", in(29));
    assertThat(service.book(command(near, UUID.randomUUID(), "key-00000006")).replayed()).isFalse();
    assertThat(count("SELECT count(*) FROM scheduling.slot WHERE status = 'free'")).isEqualTo(1);
  }

  @Test
  @DisplayName("an unknown slot is 404 not-found")
  void unknownSlotIsNotFound() {
    assertThatThrownBy(
            () -> service.book(command(UUID.randomUUID(), UUID.randomUUID(), "key-00000007")))
        .isInstanceOf(ShpException.class)
        .satisfies(e -> assertThat(code(e)).isEqualTo("not-found"));
  }

  @Test
  @DisplayName("the same key and body replays the first appointment and writes nothing new")
  void replayReturnsTheFirstAppointment() {
    UUID patient = UUID.randomUUID();
    UUID slot = slot("free", in(3));
    var first = service.book(command(slot, patient, "key-00000008"));
    var again = service.book(command(slot, patient, "key-00000008"));

    assertThat(again.replayed()).isTrue();
    assertThat(again.appointment().id()).isEqualTo(first.appointment().id());
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM scheduling.resource_history")).isEqualTo(1);
  }

  @Test
  @DisplayName("the same key with another slot is 422 idempotency-key-reused")
  void sameKeyOtherBodyIsRejected() {
    UUID patient = UUID.randomUUID();
    service.book(command(slot("free", in(3)), patient, "key-00000009"));
    assertThatThrownBy(() -> service.book(command(slot("free", in(4)), patient, "key-00000009")))
        .isInstanceOf(ShpException.class)
        .satisfies(e -> assertThat(code(e)).isEqualTo("idempotency-key-reused"));
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
  }

  @Test
  @DisplayName("the unique index backstops the claim: a failure rolls the whole booking back")
  void failureAfterTheClaimRollsEverythingBack() {
    // Inconsistent data on purpose: a free slot that already has an active appointment.
    UUID slot = slot("free", in(3));
    Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
    data.appointment(slot, UUID.randomUUID(), "booked", start);
    assertThatThrownBy(() -> service.book(command(slot, UUID.randomUUID(), "key-0000000a")))
        .isInstanceOf(SlotUnavailableException.class);

    assertThat(
            jdbc.sql("SELECT status FROM scheduling.slot WHERE id = ?")
                .param(slot)
                .query(String.class)
                .single())
        .isEqualTo("free");
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM scheduling.resource_history")).isZero();
    assertThat(count("SELECT count(*) FROM outbox")).isZero();
    assertThat(count("SELECT count(*) FROM idempotency_key")).isZero();
  }

  @Test
  @DisplayName("12 patients racing for one slot: exactly one booking, the rest are 409")
  void oneWinnerOnARace() throws Exception {
    UUID slot = slot("free", in(3));
    int n = 12;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      UUID patient = UUID.randomUUID();
      String key = "race-key-%04d".formatted(i);
      Callable<Boolean> task =
          () -> {
            go.await();
            try {
              service.book(command(slot, patient, key));
              return true;
            } catch (SlotUnavailableException e) {
              return false;
            }
          };
      results.add(pool.submit(task));
    }
    go.countDown();
    int wins = 0;
    for (Future<Boolean> f : results) {
      if (f.get()) {
        wins++;
      }
    }
    pool.shutdown();
    assertThat(wins).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
  }
}
