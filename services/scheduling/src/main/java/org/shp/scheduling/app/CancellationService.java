package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.shp.data.postgres.IdempotencyClaim;
import org.shp.data.postgres.PostgresIdempotency;
import org.shp.data.postgres.PostgresOutbox;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.scheduling.domain.AppointmentFhir;
import org.shp.scheduling.domain.AppointmentRecord;
import org.shp.scheduling.infra.AppointmentRepository;
import org.shp.scheduling.infra.SlotRepository;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code $cancel} in one transaction (02-db-scheduling §3.2, S14): claim the key, check the
 * version, apply BR-02 for a patient, change the appointment conditionally, return the slot to
 * free, write the history and the {@code appointment-cancelled} event.
 */
@Service
public class CancellationService {
  static final String EVENT_TYPE = "shp.scheduling.appointment-cancelled.v1";
  static final Duration CANCEL_WINDOW = Duration.ofHours(2);

  private final PostgresIdempotency idempotency;
  private final AppointmentRepository appointments;
  private final SlotRepository slots;
  private final PostgresOutbox outbox;
  private final Clock clock;
  private final boolean validateEvents;

  public CancellationService(
      PostgresIdempotency idempotency,
      AppointmentRepository appointments,
      SlotRepository slots,
      PostgresOutbox outbox,
      Clock clock,
      @Value("${shp.events.validate-schema:false}") boolean validateEvents) {
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency must not be null");
    this.appointments = Objects.requireNonNull(appointments, "appointments must not be null");
    this.slots = Objects.requireNonNull(slots, "slots must not be null");
    this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.validateEvents = validateEvents;
  }

  /** The cancelled appointment and whether this was a replay of an earlier request. */
  public record Result(AppointmentRecord appointment, boolean replayed) {}

  /**
   * @param expectedVersion the version of the {@code If-Match} header
   * @param staff true for a nurse
   * @throws ShpException {@code not-found} (404), {@code version-conflict} (412), {@code
   *     invalid-state-transition} (409), {@code cancellation-too-late} (422), {@code
   *     idempotency-key-reused} (422)
   */
  @Transactional
  public Result cancel(
      String subject,
      String idempotencyKey,
      byte[] requestHash,
      UUID id,
      long expectedVersion,
      String reason,
      boolean staff) {
    IdempotencyClaim claim =
        idempotency.claim(subject, idempotencyKey, "Appointment/$cancel/" + id, requestHash);
    AppointmentRecord current =
        appointments.findById(id).orElseThrow(() -> new ShpException("not-found"));
    if (claim.replay()) {
      return new Result(current, true);
    }
    if (current.versionId() != expectedVersion) {
      throw new ShpException("version-conflict");
    }
    // A nurse may also cancel an arrived appointment, with reason other (08 S14).
    boolean arrivedByNurse = staff && "arrived".equals(current.status()) && "other".equals(reason);
    if (!"booked".equals(current.status()) && !arrivedByNurse) {
      throw new ShpException("invalid-state-transition");
    }
    Instant now = clock.instant();
    Duration remaining = Duration.between(now, current.start());
    if (!staff && remaining.compareTo(CANCEL_WINDOW) < 0) {
      throw new ShpException("cancellation-too-late");
    }
    String content =
        AppointmentFhir.cancelled(current.content(), current.versionId() + 1, now, reason);
    AppointmentRecord cancelled =
        appointments
            .cancel(
                current,
                arrivedByNurse ? List.of("arrived") : List.of("booked"),
                reason,
                subject,
                now,
                content)
            .orElseThrow(() -> new ShpException("version-conflict"));
    slots.releaseIfFuture(current.slotId(), now);
    outbox.add(cancelledEvent(cancelled, reason, staff, now, remaining), validateEvents);
    return new Result(cancelled, false);
  }

  private CloudEvent cancelledEvent(
      AppointmentRecord a, String reason, boolean staff, Instant now, Duration remaining) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("appointmentId", a.id().toString());
    data.put("version", a.versionId());
    data.put("patientId", a.patientId().toString());
    data.put("practitionerId", a.practitionerId().toString());
    data.put("start", a.start().toString());
    data.put("cancelReason", reason);
    data.put("cancelledBy", staff ? "staff" : "patient");
    data.put("cancelledAt", now.toString());
    data.put("hoursBeforeStart", remaining.toSeconds() / 3600.0);
    return CloudEvents.create("scheduling", EVENT_TYPE, "Appointment/" + a.id(), data, clock, null);
  }
}
