package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.shp.data.postgres.PostgresOutbox;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.scheduling.domain.AppointmentFhir;
import org.shp.scheduling.domain.AppointmentRecord;
import org.shp.scheduling.infra.AppointmentRepository;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S16 {@code $check-in} in one transaction (08 §4, 13 §3 E4): moves a {@code booked} appointment to
 * {@code arrived} within 60 minutes before to 30 minutes after {@code start}, writes {@code
 * resource_history}, and queues {@code shp.scheduling.appointment-checked-in.v1} in the outbox.
 */
@Service
public class CheckInService {
  /** Earliest check-in relative to {@code start}: 60 minutes before. */
  public static final Duration BEFORE_START = Duration.ofMinutes(60);

  /** Latest check-in relative to {@code start}: 30 minutes after. */
  public static final Duration AFTER_START = Duration.ofMinutes(30);

  static final String EVENT_TYPE = "shp.scheduling.appointment-checked-in.v1";

  private final AppointmentRepository appointments;
  private final PostgresOutbox outbox;
  private final Clock clock;
  private final boolean validateEvents;

  public CheckInService(
      AppointmentRepository appointments,
      PostgresOutbox outbox,
      Clock clock,
      @Value("${shp.events.validate-schema:false}") boolean validateEvents) {
    this.appointments = Objects.requireNonNull(appointments, "appointments must not be null");
    this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.validateEvents = validateEvents;
  }

  /**
   * Checks in a {@code booked} appointment when {@code clock.instant()} is within {@code [start -
   * 60 min, start + 30 min]}.
   *
   * @throws ShpException {@code not-found} (404), {@code version-conflict} (412), {@code
   *     invalid-state-transition} (409), or {@code checkin-out-of-window} (422)
   */
  @Transactional
  public AppointmentRecord checkIn(UUID appointmentId, long expectedVersion, String changedBy) {
    Objects.requireNonNull(appointmentId, "appointmentId must not be null");
    Objects.requireNonNull(changedBy, "changedBy must not be null");

    AppointmentRecord current =
        appointments.findById(appointmentId).orElseThrow(() -> new ShpException("not-found"));
    if (current.versionId() != expectedVersion) {
      throw new ShpException("version-conflict");
    }
    if (!"booked".equals(current.status())) {
      throw new ShpException("invalid-state-transition");
    }

    Instant now = clock.instant();
    if (now.isBefore(current.start().minus(BEFORE_START))
        || now.isAfter(current.start().plus(AFTER_START))) {
      throw new ShpException("checkin-out-of-window");
    }

    int newVersion = current.versionId() + 1;
    String newContent = AppointmentFhir.arrived(current.content(), newVersion, now);
    AppointmentRecord updated =
        appointments
            .checkIn(current, changedBy, now, newContent)
            .orElseThrow(() -> whyNotCheckedIn(appointmentId, expectedVersion));

    outbox.add(checkedInEvent(updated, now), validateEvents);
    return updated;
  }

  private ShpException whyNotCheckedIn(UUID appointmentId, long expectedVersion) {
    return appointments
        .findById(appointmentId)
        .map(current ->
            current.versionId() != expectedVersion
                ? new ShpException("version-conflict")
                : new ShpException("invalid-state-transition"))
        .orElseGet(() -> new ShpException("not-found"));
  }

  private CloudEvent checkedInEvent(AppointmentRecord a, Instant checkedInAt) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("appointmentId", a.id().toString());
    data.put("version", a.versionId());
    data.put("patientId", a.patientId().toString());
    data.put("practitionerId", a.practitionerId().toString());
    data.put("specialty", a.specialty());
    data.put("checkedInAt", checkedInAt.toString());
    return CloudEvents.create("scheduling", EVENT_TYPE, "Appointment/" + a.id(), data, clock, null);
  }
}
