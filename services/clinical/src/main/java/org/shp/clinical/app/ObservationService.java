package org.shp.clinical.app;

import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.shp.events.Uuids;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * The Observation API (09 C16; US-025): record vital signs and laboratory results, read them, and
 * list them by patient. The profile, units, ranges and interpretation are checked by {@link
 * ObservationValidator} (CLN-16); this service owns the identifiers, the performer and the link to
 * the Encounter.
 */
@Service
public class ObservationService {
  static final String FINISHED = "finished";

  public record View(Observation resource, int version) {}

  private final Observations observations;
  private final Encounters encounters;
  private final ObservationValidator validator;
  private final Clock clock;

  public ObservationService(
      Observations observations,
      Encounters encounters,
      ObservationValidator validator,
      Clock clock) {
    this.observations = observations;
    this.encounters = encounters;
    this.validator = validator;
    this.clock = clock;
  }

  /**
   * C16 create. {@code id}, {@code meta} and {@code performer} are the server's (the performer is
   * the caller); the Encounter must exist, belong to the same patient and not be finished.
   *
   * @param practitionerId the caller's Practitioner id
   * @param doctor whether the caller is a doctor: manual laboratory results are doctors' only (09
   *     C16), while the matrix lets a nurse create any Observation
   */
  public View create(String body, String practitionerId, boolean doctor, String changedBy) {
    Observation o = FhirJson.parse(body, Observation.class);
    if (practitionerId == null) {
      throw new ShpException("forbidden", "Only a practitioner can record an Observation.");
    }
    if (!doctor
        && ObservationValidator.LABORATORY.equals(
            o.getCategoryFirstRep().getCodingFirstRep().getCode())) {
      throw new ShpException("forbidden", "Only a doctor can record a laboratory result.");
    }
    String id = Uuids.v7(clock).toString();
    o.setId(id);
    o.setMeta(null);
    o.setPerformer(List.of(new Reference("Practitioner/" + practitionerId)));
    validator.validate(o); // 400 validation-failed or 422 value-out-of-range
    String patientId = o.getSubject().getReference().substring("Patient/".length());
    String encounterId = null;
    if (o.hasEncounter()) {
      encounterId = o.getEncounter().getReference().substring("Encounter/".length());
      var encounter =
          encounters
              .find(encounterId)
              .orElseThrow(() ->
                  new ShpException("validation-failed", "Observation.encounter does not exist."));
      if (!patientId.equals(encounter.patientId())) {
        throw new ShpException(
            "validation-failed", "Observation.encounter belongs to another patient.");
      }
      if (FINISHED.equals(encounter.search().getString("status"))) {
        throw new ShpException(
            "invalid-state-transition", "The Encounter is finished; nothing can be added.");
      }
    }
    var stored =
        observations.create(id, patientId, toDocument(o), search(o, encounterId), changedBy);
    return view(stored);
  }

  public View read(String id) {
    return view(observations.find(id).orElseThrow(() -> new ShpException("not-found")));
  }

  /**
   * C16 search by patient. A Patient caller sees only Observations of their finished Encounters (15
   * §3: "a Patient sees only observations belonging to their own finished Encounter").
   */
  public List<Observation> byPatient(
      String patientId, boolean finishedEncountersOnly, String category, String code, int count) {
    Set<String> encounterIds = null;
    if (finishedEncountersOnly) {
      encounterIds =
          encounters.byPatient(patientId, FINISHED, 1_000).stream()
              .map(Encounters.Stored::id)
              .collect(Collectors.toSet());
      if (encounterIds.isEmpty()) {
        return List.of();
      }
    }
    return observations.byPatient(patientId, encounterIds, category, code, count).stream()
        .map(s -> view(s).resource())
        .toList();
  }

  /** 03 §2.2 search fields of {@code observations}. */
  static Document search(Observation o, String encounterId) {
    return new Document("encounterId", encounterId)
        .append("category", o.getCategoryFirstRep().getCodingFirstRep().getCode())
        .append("code", o.getCode().getCodingFirstRep().getCode())
        .append("effective", Date.from(o.getEffectiveDateTimeType().getValue().toInstant()))
        .append(
            "interpretation",
            o.hasInterpretation()
                ? o.getInterpretationFirstRep().getCodingFirstRep().getCode()
                : null);
  }

  static Document toDocument(Observation o) {
    Observation copy = o.copy();
    copy.setMeta(null);
    return Document.parse(FhirJson.serialize(copy));
  }

  private static View view(Observations.Stored s) {
    Observation o = FhirJson.parse(s.resource().toJson(), Observation.class);
    o.setId(s.id());
    o.getMeta().setVersionId(Integer.toString(s.version()));
    return new View(o, s.version());
  }
}
