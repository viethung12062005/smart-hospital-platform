package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.shp.data.postgres.IdempotencyClaim;
import org.shp.data.postgres.PostgresOutbox;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.scheduling.domain.AppointmentFhir;
import org.shp.scheduling.domain.AppointmentRecord;
import org.shp.scheduling.domain.BookingCommand;
import org.shp.scheduling.domain.BookingResult;
import org.shp.scheduling.domain.SlotUnavailableException;
import org.shp.scheduling.infra.AppointmentRepository;
import org.shp.scheduling.infra.SlotRepository;
import org.shp.scheduling.infra.SlotRepository.SlotFacts;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code $book} in one READ COMMITTED transaction (02-db-scheduling §3.1): claim the key, lock the
 * patient, check BR-03 (SCH-12, {@link BookingGuard}), then compare-and-set the slot, check BR-04,
 * create the appointment, history and the {@code appointment-booked} event (SCH-13). Any exception
 * rolls everything back.
 */
@Service
public class BookingService {
  /** BR-04: a slot can be booked at most this long before it starts. */
  public static final Duration BOOKING_WINDOW = Duration.ofDays(30);

  static final String EVENT_TYPE = "shp.scheduling.appointment-booked.v1";

  private final BookingGuard guard;
  private final SlotRepository slots;
  private final AppointmentRepository appointments;
  private final PostgresOutbox outbox;
  private final Clock clock;
  private final boolean validateEvents;

  public BookingService(
      BookingGuard guard,
      SlotRepository slots,
      AppointmentRepository appointments,
      PostgresOutbox outbox,
      Clock clock,
      @Value("${shp.events.validate-schema:false}") boolean validateEvents) {
    this.guard = Objects.requireNonNull(guard, "guard must not be null");
    this.slots = Objects.requireNonNull(slots, "slots must not be null");
    this.appointments = Objects.requireNonNull(appointments, "appointments must not be null");
    this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.validateEvents = validateEvents;
  }

  /**
   * @throws ShpException {@code max-future-appointments} / {@code idempotency-key-reused} / {@code
   *     outside-booking-window} (422), {@code not-found} (404)
   * @throws SlotUnavailableException 409 {@code slot-unavailable}
   */
  @Transactional
  public BookingResult book(BookingCommand cmd) {
    return book(cmd, () -> {});
  }

  /**
   * Like {@link #book(BookingCommand)}, and runs {@code afterClaim} once the key is claimed and the
   * request is not a replay, before the slot is taken. It must not call another service: do that
   * before this method and pass the outcome in. A replay never runs it, so a retry still gets the
   * stored answer when a dependency checked there is down.
   */
  @Transactional
  public BookingResult book(BookingCommand cmd, Runnable afterClaim) {
    IdempotencyClaim claim =
        guard.begin(cmd.subject(), cmd.idempotencyKey(), cmd.requestHash(), cmd.patientId());
    if (claim.replay()) {
      AppointmentRecord first =
          appointments
              .findOriginalBooking(claim.resourceId())
              .orElseThrow(
                  () -> new IllegalStateException("idempotency claim without an appointment"));
      return new BookingResult(first, true);
    }

    afterClaim.run();

    Instant now = clock.instant();
    SlotFacts slot =
        slots
            .claimFree(cmd.slotId(), now, now.plus(BOOKING_WINDOW))
            .orElseThrow(() -> whyNotClaimed(cmd, now));

    AppointmentRecord draft =
        new AppointmentRecord(
            claim.resourceId(),
            1,
            now,
            "booked",
            slot.id(),
            cmd.patientId(),
            slot.practitionerId(),
            slot.specialty(),
            slot.start(),
            slot.end(),
            cmd.channel(),
            cmd.subject(),
            "{}");
    AppointmentRecord booked = draft.withContent(AppointmentFhir.booked(draft, cmd.comment()));
    String storedContent;
    try {
      storedContent = appointments.insertWithHistory(booked);
    } catch (DuplicateKeyException e) {
      // Final guard (unique index): cannot happen after a successful claim, but never double-book.
      throw new SlotUnavailableException(slot.id(), slot.practitionerId(), slot.specialty());
    }
    outbox.add(bookedEvent(booked, now), validateEvents);
    return new BookingResult(booked.withContent(storedContent), false);
  }

  /** Step 4, zero rows: not found 404, outside the window 422, otherwise 409. */
  private ShpException whyNotClaimed(BookingCommand cmd, Instant now) {
    return slots
        .find(cmd.slotId())
        .<ShpException>map(s ->
            !s.start().isBefore(now.plus(BOOKING_WINDOW))
                ? new ShpException("outside-booking-window")
                : new SlotUnavailableException(s.id(), s.practitionerId(), s.specialty()))
        .orElseGet(() -> new ShpException("not-found"));
  }

  private CloudEvent bookedEvent(AppointmentRecord a, Instant bookedAt) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("appointmentId", a.id().toString());
    data.put("version", a.versionId());
    data.put("patientId", a.patientId().toString());
    data.put("practitionerId", a.practitionerId().toString());
    data.put("slotId", a.slotId().toString());
    data.put("specialty", a.specialty());
    data.put("start", a.start().toString());
    data.put("end", a.end().toString());
    data.put("channel", a.channel());
    data.put("bookedAt", bookedAt.toString());
    return CloudEvents.create("scheduling", EVENT_TYPE, "Appointment/" + a.id(), data, clock, null);
  }
}
