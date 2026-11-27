package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hl7.fhir.r4.model.Appointment;
import org.shp.data.postgres.PostgresOutbox;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.domain.AppointmentRecord;
import org.shp.scheduling.infra.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E6 {@code encounter-finished} (13-events §2): an {@code arrived} Appointment becomes {@code
 * fulfilled} and {@code appointment-status-changed} (E5) goes to the outbox in the same
 * transaction. Any other state (already fulfilled, cancelled, noshow, booked, unknown appointment)
 * means a duplicate or stale event: nothing changes and nothing is emitted.
 */
@Service
public class AppointmentFulfillmentService {
  private static final Logger LOG = LoggerFactory.getLogger(AppointmentFulfillmentService.class);

  public static final String CONSUMER = "scheduling";
  public static final String FINISHED_TYPE = "shp.clinical.encounter-finished.v1";
  static final String CHANGED_TYPE = "shp.scheduling.appointment-status-changed.v1";
  static final String CHANGED_BY = "system:encounter-finished";

  private final AppointmentRepository appointments;
  private final PostgresOutbox outbox;
  private final Clock clock;
  private final boolean validateEvents;

  public AppointmentFulfillmentService(
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
   * Returns true when the appointment moved to {@code fulfilled}, false when the event was stale.
   */
  @Transactional
  public boolean onEncounterFinished(CloudEvent event) {
    UUID appointmentId = UUID.fromString(event.data().get("appointmentId").asString());
    AppointmentRecord current = appointments.findByIdForUpdate(appointmentId).orElse(null);
    if (current == null || !"arrived".equals(current.status())) {
      LOG.info(
          "encounter-finished ignored: appointment {} is {}",
          appointmentId,
          current == null ? "unknown" : current.status());
      return false;
    }
    Instant now = clock.instant();
    int next = current.versionId() + 1;
    String content = fulfilledContent(current, next, now);
    if (!appointments.markFulfilled(appointmentId, current.versionId(), now, CHANGED_BY, content)) {
      return false;
    }
    outbox.add(changedEvent(current, next, now), validateEvents);
    return true;
  }

  private static String fulfilledContent(AppointmentRecord a, int version, Instant now) {
    var resource = FhirJson.parse(a.content(), Appointment.class);
    resource.setStatus(Appointment.AppointmentStatus.FULFILLED);
    resource.getMeta().setVersionId(Integer.toString(version));
    resource.getMeta().setLastUpdated(Date.from(now));
    return FhirJson.serialize(resource);
  }

  private CloudEvent changedEvent(AppointmentRecord a, int version, Instant now) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("appointmentId", a.id().toString());
    data.put("version", version);
    data.put("patientId", a.patientId().toString());
    data.put("previousStatus", "arrived");
    data.put("newStatus", "fulfilled");
    data.put("changedAt", now.toString());
    return CloudEvents.create(
        "scheduling", CHANGED_TYPE, "Appointment/" + a.id(), data, clock, null);
  }
}
