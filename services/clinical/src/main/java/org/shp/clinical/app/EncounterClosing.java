package org.shp.clinical.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bson.Document;
import org.hl7.fhir.r4.model.Encounter;
import org.shp.clinical.app.Encounters.Stored;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.Uuids;
import org.shp.web.errors.ErrorInfo;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * Ending a visit (09 C14; US-028): {@code in-progress → finished} once at least one diagnosis is
 * recorded, with {@code shp.clinical.encounter-finished.v1} (E6) in the same transaction.
 */
@Service
public class EncounterClosing {
  public static final String FINISHED_TYPE = "shp.clinical.encounter-finished.v1";
  static final String SERVICE = "clinical";

  /** 422 {@code diagnosis-required} (09 C14); not yet in the shared catalog (07 §5). */
  public static final ErrorInfo DIAGNOSIS_REQUIRED =
      new ErrorInfo(
          "diagnosis-required",
          422,
          "business-rule",
          "Cần ít nhất một chẩn đoán trước khi kết thúc lượt khám.",
          "At least one diagnosis is required before finishing the visit.");

  /** Thrown for {@link #DIAGNOSIS_REQUIRED}. */
  public static class DiagnosisRequiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DiagnosisRequiredException() {
      super("At least one encounter-diagnosis Condition is required.");
    }
  }

  private final Encounters encounters;
  private final Conditions conditions;
  private final Clock clock;

  public EncounterClosing(Encounters encounters, Conditions conditions, Clock clock) {
    this.encounters = encounters;
    this.conditions = conditions;
    this.clock = clock;
  }

  /**
   * C14 {@code $finish}: only an {@code in-progress} visit (409 otherwise), only with an {@code
   * encounter-diagnosis} that is not {@code entered-in-error} (422 {@code diagnosis-required}).
   * Sets {@code period.end}, the search status and end, and publishes E6 through the outbox.
   *
   * @param practitionerId the calling doctor ({@code null} for any other caller → 403)
   */
  public EncounterService.View finish(String id, String practitionerId, String changedBy) {
    if (practitionerId == null) {
      throw new ShpException("forbidden", "Only a doctor can finish the visit.");
    }
    Stored current = encounters.find(id).orElseThrow(() -> new ShpException("not-found"));
    Encounter e = EncounterService.parse(current.resource());
    if (!"in-progress".equals(e.getStatus().toCode())) {
      throw new ShpException("invalid-state-transition", "Encounter.status must be in-progress.");
    }
    boolean diagnosed =
        conditions.byEncounter(id).stream()
            .anyMatch(c ->
                ConditionService.ENCOUNTER_DIAGNOSIS.equals(c.search().getString("category"))
                    && !ConditionService.ENTERED_IN_ERROR.equals(
                        c.search().getString("verificationStatus")));
    if (!diagnosed) {
      throw new DiagnosisRequiredException();
    }
    Instant now = clock.instant();
    e.setStatus(Encounter.EncounterStatus.FINISHED);
    e.getPeriod().setEnd(Date.from(now));
    Document search =
        new Document(current.search())
            .append("status", "finished")
            .append("periodEnd", Date.from(now));
    int newVersion = current.version() + 1;
    CloudEvent event = finished(current, now, newVersion);
    return EncounterService.view(
        encounters.update(
            current, EncounterService.toDocument(e), search, current.version(), changedBy, event));
  }

  /** E6 (13 §3): ids, time and the Encounter version; no PII. */
  CloudEvent finished(Stored current, Instant at, int version) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("encounterId", current.id());
    data.put("appointmentId", current.search().getString("appointmentId"));
    data.put("patientId", current.patientId());
    data.put("practitionerId", current.search().getString("practitionerId"));
    data.put("finishedAt", at.toString());
    data.put("version", version);
    return CloudEvents.create(
        SERVICE, FINISHED_TYPE, "Encounter/" + current.id(), data, clock, Uuids.v7(clock));
  }
}
