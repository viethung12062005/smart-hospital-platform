package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.shp.events.CloudEvent;
import org.shp.scheduling.domain.AppointmentFhir;
import org.shp.scheduling.domain.AppointmentRecord;
import org.shp.scheduling.infra.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * E7 {@code patient-merged} (13 §3): reassigns every scheduling appointment of {@code
 * sourcePatientId} to {@code targetPatientId} and appends version history inside the inbox
 * transaction. Safe and idempotent when delivered again or rerun.
 */
@Service
public class PatientMergeService {
  private static final Logger LOG = LoggerFactory.getLogger(PatientMergeService.class);

  public static final String CONSUMER = "scheduling";
  public static final String PATIENT_MERGED_TYPE = "shp.clinical.patient-merged.v1";
  static final String CHANGED_BY = "system/patient-merged";

  private final AppointmentRepository appointments;
  private final Clock clock;

  public PatientMergeService(AppointmentRepository appointments, Clock clock) {
    this.appointments = Objects.requireNonNull(appointments, "appointments must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Reassigns all appointments owned by {@code sourcePatientId} to {@code targetPatientId}. Must
   * execute inside an active transaction (opened by {@code PostgresInbox.handleOnce}).
   */
  public int onPatientMerged(CloudEvent event) {
    UUID sourcePatientId = requiredUuid(event.data(), "sourcePatientId");
    UUID targetPatientId = requiredUuid(event.data(), "targetPatientId");
    if (sourcePatientId.equals(targetPatientId)) {
      return 0;
    }

    List<AppointmentRecord> rows = appointments.lockByPatientId(sourcePatientId);
    if (rows.isEmpty()) {
      return 0;
    }

    Instant now = clock.instant();
    for (AppointmentRecord current : rows) {
      int newVersion = current.versionId() + 1;
      String newContent =
          AppointmentFhir.reassignPatient(
              current.content(), sourcePatientId, targetPatientId, newVersion, now);
      appointments.reassignPatient(current, targetPatientId, CHANGED_BY, now, newContent);
    }
    LOG.info("Reassigned {} appointments for patient-merged event", rows.size());
    return rows.size();
  }

  private static UUID requiredUuid(JsonNode data, String field) {
    JsonNode node = data.get(field);
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException("Event data is missing " + field);
    }
    return UUID.fromString(node.asString());
  }
}
