package org.shp.scheduling.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.data.postgres.IdempotencyClaim;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-12 (US-015): {@code $book} step 1 on real PostgreSQL 16 - Idempotency-Key claim, advisory
 * lock per patient and BR-03 (02-db-scheduling §3.1, steps 1-3).
 */
@SpringBootTest(
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
class BookingGuardTest {
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

  @Autowired BookingGuard guard;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;

  private BookingTestSupport data;
  private UUID doctor;
  private UUID schedule;
  private int slotCounter;

  @BeforeEach
  void seed() {
    data = new BookingTestSupport(jdbc);
    data.clean();
    doctor = UUID.randomUUID();
    schedule = data.schedule(doctor, "cardiology");
    slotCounter = 0;
  }

  private Instant nextStart(long daysAhead) {
    return Instant.now().plus(daysAhead, ChronoUnit.DAYS).plusSeconds(900L * ++slotCounter);
  }

  private void appointment(UUID patient, String status, long daysAhead) {
    Instant start = nextStart(daysAhead);
    UUID slot = data.slot(schedule, doctor, "cardiology", "busy", start);
    data.appointment(slot, patient, status, start);
  }

  private static byte[] hash(String s) {
    return org.shp.web.idempotency.IdempotencyKeys.hashBody(
        ("{\"slot\":\"" + s + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("a first request claims the key and gets a fresh appointment id")
  void firstRequestClaims() {
    UUID patient = UUID.randomUUID();
    IdempotencyClaim claim =
        tx.execute(s -> guard.begin("sub-1", "key-00000001", hash("a"), patient));
    assertThat(claim.replay()).isFalse();
    assertThat(claim.resourceId()).isNotNull();
    assertThat(
            jdbc.sql("SELECT count(*) FROM idempotency_key WHERE key = 'key-00000001'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("a patient with 3 future booked appointments gets 422 max-future-appointments")
  void thirdFutureAppointmentIsTheLimit() {
    UUID patient = UUID.randomUUID();
    appointment(patient, "booked", 2);
    appointment(patient, "booked", 3);
    // two future appointments: a third is allowed
    assertThat(tx.execute(s -> guard.begin("sub-1", "key-00000002", hash("a"), patient)).replay())
        .isFalse();
    appointment(patient, "booked", 4);

    assertThatThrownBy(
            () -> tx.execute(s -> guard.begin("sub-1", "key-00000003", hash("b"), patient)))
        .isInstanceOf(ShpException.class)
        .extracting(e -> ((ShpException) e).info().code())
        .isEqualTo("max-future-appointments");
    // the rejected request leaves no claim behind: the same key can be retried later
    assertThat(
            jdbc.sql("SELECT count(*) FROM idempotency_key WHERE key = 'key-00000003'")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("past, cancelled and other patients' appointments do not count toward BR-03")
  void onlyFutureBookedAppointmentsOfThePatientCount() {
    UUID patient = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    appointment(patient, "booked", -3);
    appointment(patient, "cancelled", 2);
    appointment(patient, "noshow", -2);
    appointment(other, "booked", 2);
    appointment(other, "booked", 3);
    appointment(other, "booked", 4);
    appointment(patient, "booked", 5);
    appointment(patient, "booked", 6);

    assertThat(tx.execute(s -> guard.begin("sub-1", "key-00000004", hash("a"), patient)).replay())
        .isFalse();
  }

  @Test
  @DisplayName("same key and same body replays the first resource id even at the limit")
  void sameKeyAndBodyReplays() {
    UUID patient = UUID.randomUUID();
    IdempotencyClaim first =
        tx.execute(s -> guard.begin("sub-1", "key-00000005", hash("a"), patient));
    appointment(patient, "booked", 2);
    appointment(patient, "booked", 3);
    appointment(patient, "booked", 4);

    IdempotencyClaim again =
        tx.execute(s -> guard.begin("sub-1", "key-00000005", hash("a"), patient));
    assertThat(again.replay()).isTrue();
    assertThat(again.resourceId()).isEqualTo(first.resourceId());
  }

  @Test
  @DisplayName("same key with a different body is 422 idempotency-key-reused")
  void sameKeyDifferentBodyIsRejected() {
    UUID patient = UUID.randomUUID();
    tx.execute(s -> guard.begin("sub-1", "key-00000006", hash("a"), patient));
    assertThatThrownBy(
            () -> tx.execute(s -> guard.begin("sub-1", "key-00000006", hash("b"), patient)))
        .isInstanceOf(ShpException.class)
        .extracting(e -> ((ShpException) e).info().code())
        .isEqualTo("idempotency-key-reused");
  }

  @Test
  @DisplayName("running outside a transaction is a programming error")
  void requiresATransaction() {
    assertThatThrownBy(() -> guard.begin("sub-1", "key-00000007", hash("a"), UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("two bookings of the same patient serialize; another patient is not blocked")
  void sameCustomerSerializes() throws Exception {
    UUID patient = UUID.randomUUID();
    appointment(patient, "booked", 2);
    appointment(patient, "booked", 3);
    CountDownLatch firstHoldsLock = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);

    // Request A holds the patient lock, then becomes the third appointment and commits.
    CompletableFuture<Void> a =
        CompletableFuture.runAsync(() ->
            tx.executeWithoutResult(s -> {
              guard.begin("sub-1", "key-0000000a", hash("a"), patient);
              firstHoldsLock.countDown();
              await(releaseFirst);
              appointment(patient, "booked", 4);
            }));
    assertThat(firstHoldsLock.await(20, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<Object> b =
        CompletableFuture.supplyAsync(
            () -> tx.execute(s -> guard.begin("sub-2", "key-0000000b", hash("b"), patient)));
    // B waits for A (same patient) ...
    assertThatThrownBy(() -> b.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
    // ... while a different patient passes straight through.
    assertThat(
            tx.execute(s -> guard.begin("sub-3", "key-0000000c", hash("c"), UUID.randomUUID()))
                .replay())
        .isFalse();

    releaseFirst.countDown();
    a.get(20, TimeUnit.SECONDS);
    // B now sees A's committed third appointment and is rejected by BR-03.
    assertThatThrownBy(() -> b.get(20, TimeUnit.SECONDS))
        .hasRootCauseInstanceOf(ShpException.class)
        .rootCause()
        .extracting(e -> ((ShpException) e).info().code())
        .isEqualTo("max-future-appointments");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
