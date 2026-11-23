package org.shp.clinical.app;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Patient.LinkType;
import org.hl7.fhir.r4.model.Reference;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.Uuids;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * 09 C8 {@code $merge} (US-004): a nurse merges a duplicate record into the one that stays. The
 * source becomes {@code active = false} with {@code link[replaced-by] → target}; both keep their
 * version history; {@code shp.clinical.patient-merged.v1} (E7) is written to the outbox in the same
 * transaction, so the other services move their references (13 §3).
 */
@Service
public class PatientMergeService {
  public static final String MERGED_TYPE = "shp.clinical.patient-merged.v1";
  static final String SERVICE = "clinical";

  private final PatientRepository patients;
  private final Clock clock;

  public PatientMergeService(PatientRepository patients, Clock clock) {
    this.patients = patients;
    this.clock = clock;
  }

  /**
   * @param targetReference {@code Patient/{id}} of the record that stays
   * @return the updated source record
   * @throws ShpException 400 bad target (or the same record), 404 unknown source or target, 409
   *     {@code invalid-state-transition} when either record is already merged or inactive
   */
  public Entry merge(String sourceId, String targetReference, String changedBy) {
    if (targetReference == null || !targetReference.matches("^Patient/[A-Za-z0-9-]{1,64}$")) {
      throw new ShpException("validation-failed", "target must reference Patient/{id}.");
    }
    String targetId = targetReference.substring("Patient/".length());
    if (targetId.equals(sourceId)) {
      throw new ShpException("validation-failed", "target must be another record.");
    }
    Entry source = patients.find(sourceId).orElseThrow(() -> new ShpException("not-found"));
    Entry target = patients.find(targetId).orElseThrow(() -> new ShpException("not-found"));
    Patient s = PatientRepository.toFhir(source);
    Patient t = PatientRepository.toFhir(target);
    if (merged(s) || merged(t)) {
      throw new ShpException(
          "invalid-state-transition", "A record that is already merged cannot be merged again.");
    }
    s.setActive(false);
    s.addLink().setOther(new Reference("Patient/" + targetId)).setType(LinkType.REPLACEDBY);
    Instant now = clock.instant();
    return patients.update(s, source.versionId(), changedBy, event(sourceId, targetId, now));
  }

  /** Inactive, or already replaced by another record. */
  static boolean merged(Patient p) {
    return (p.hasActive() && !p.getActive())
        || p.getLink().stream().anyMatch(l -> l.getType() == LinkType.REPLACEDBY);
  }

  /** E7 (13 §3): ids and time only, no PII. */
  CloudEvent event(String sourceId, String targetId, Instant at) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("sourcePatientId", sourceId);
    data.put("targetPatientId", targetId);
    data.put("mergedAt", at.toString());
    return CloudEvents.create(
        SERVICE, MERGED_TYPE, "Patient/" + sourceId, data, clock, Uuids.v7(clock));
  }
}
