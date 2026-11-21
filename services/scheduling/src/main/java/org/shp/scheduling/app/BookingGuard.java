package org.shp.scheduling.app;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.shp.data.postgres.IdempotencyClaim;
import org.shp.data.postgres.PostgresIdempotency;
import org.shp.web.errors.ShpException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code $book} step 1 (02-db-scheduling §3.1, steps 1-3): claim the Idempotency-Key, serialize
 * bookings of the same patient with a transaction-scoped advisory lock, then enforce BR-03 (at most
 * 3 future appointments per patient). Everything runs in the caller's transaction, so a failure
 * here rolls the claim back and the key can be used again.
 */
@Component
public class BookingGuard {
  /** Operation name stored with the idempotency key (02-db-scheduling §2). */
  public static final String OPERATION = "Appointment/$book";

  /** BR-03: the number of future appointments that blocks a new booking. */
  public static final int MAX_FUTURE_APPOINTMENTS = 3;

  private final PostgresIdempotency idempotency;
  private final JdbcClient jdbc;
  private final Clock clock;

  public BookingGuard(PostgresIdempotency idempotency, JdbcClient jdbc, Clock clock) {
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Run steps 1-3. A replay ({@link IdempotencyClaim#replay()}) skips the lock and the BR-03 check:
   * the caller returns the appointment created by the first request.
   *
   * @throws ShpException {@code idempotency-key-reused} (422) when the key was used with another
   *     body, {@code max-future-appointments} (422) when BR-03 is violated
   * @throws IllegalStateException when called outside a transaction
   */
  public IdempotencyClaim begin(String subject, String key, byte[] requestHash, UUID patientId) {
    Objects.requireNonNull(patientId, "patientId must not be null");
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("BookingGuard.begin must run in the booking transaction");
    }
    IdempotencyClaim claim = idempotency.claim(subject, key, OPERATION, requestHash);
    if (claim.replay()) {
      return claim;
    }
    lockPatient(patientId);
    int future = countFutureAppointments(patientId);
    if (future >= MAX_FUTURE_APPOINTMENTS) {
      throw new ShpException("max-future-appointments");
    }
    return claim;
  }

  /** Step 2: bookings of one patient wait for each other; other patients are not affected. */
  private void lockPatient(UUID patientId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('appt-patient:' || ?, 0))")
        .param(patientId.toString())
        .query()
        .singleValue();
  }

  /** Step 3: BR-03 counts {@code booked} appointments that start in the future, per profile. */
  private int countFutureAppointments(UUID patientId) {
    return jdbc.sql(
            "SELECT count(*) FROM scheduling.appointment WHERE patient_id = ? AND status = 'booked' AND start_at > ?")
        .params(patientId, Timestamp.from(clock.instant()))
        .query(Integer.class)
        .single();
  }
}
