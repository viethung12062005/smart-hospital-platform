package org.shp.clinical.app;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.bson.Document;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.AllergyIntolerance.AllergyIntoleranceCriticality;
import org.hl7.fhir.r4.model.AllergyIntolerance.AllergyIntoleranceReactionComponent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Reference;
import org.shp.events.Uuids;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * The AllergyIntolerance API (09 C18; 01 §4.9; US-027): a doctor with care or a nurse records and
 * updates a patient's allergies; the patient reads their own. The stored resource is rebuilt from
 * the profile's elements only, so anything else in the body is dropped; {@code recorder} and {@code
 * recordedDate} are the server's.
 */
@Service
public class AllergyService {
  public static final String CLINICAL =
      "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical";
  static final Set<String> STATUSES = Set.of("active", "inactive");
  static final int MAX_TEXT = 200;

  public record View(AllergyIntolerance resource, int version) {}

  private final Allergies allergies;
  private final Clock clock;

  public AllergyService(Allergies allergies, Clock clock) {
    this.allergies = allergies;
    this.clock = clock;
  }

  /** C18 create; {@code practitionerId} = the caller (doctor or nurse). */
  public View create(String body, String practitionerId, String changedBy) {
    if (practitionerId == null) {
      throw new ShpException("forbidden", "Only a practitioner can record an allergy.");
    }
    AllergyIntolerance sent = FhirJson.parse(body, AllergyIntolerance.class);
    String patientId = patient(sent);
    String id = Uuids.v7(clock).toString();
    AllergyIntolerance a = profile(sent);
    a.setId(id);
    a.setPatient(new Reference("Patient/" + patientId));
    a.setRecorder(new Reference("Practitioner/" + practitionerId));
    a.setRecordedDateElement(
        new DateTimeType(
            Date.from(clock.instant()), TemporalPrecisionEnum.SECOND, TimeZone.getTimeZone("UTC")));
    return view(allergies.create(id, patientId, toDocument(a), search(a), changedBy));
  }

  /**
   * C18 update with {@code If-Match}: {@code clinicalStatus}, {@code code.text}, {@code
   * criticality} and {@code reaction} change; patient, recorder and recordedDate keep their stored
   * values.
   */
  public View update(String id, String body, int expectedVersion, String changedBy) {
    AllergyIntolerance sent = FhirJson.parse(body, AllergyIntolerance.class);
    if (sent.hasIdElement() && !id.equals(sent.getIdElement().getIdPart())) {
      throw invalid("AllergyIntolerance.id must match the URL.");
    }
    var current = allergies.find(id).orElseThrow(() -> new ShpException("not-found"));
    AllergyIntolerance stored =
        FhirJson.parse(current.resource().toJson(), AllergyIntolerance.class);
    AllergyIntolerance next = profile(sent);
    next.setId(id);
    next.setPatient(stored.getPatient());
    next.setRecorder(stored.getRecorder());
    next.setRecordedDateElement(stored.getRecordedDateElement());
    return view(
        allergies.update(current, toDocument(next), search(next), expectedVersion, changedBy));
  }

  /** C18 list by patient (15 §6: a patient reads their own, the controller forces the id). */
  public List<AllergyIntolerance> byPatient(String patientId, int count) {
    return allergies.byPatient(patientId, count).stream().map(s -> view(s).resource()).toList();
  }

  // ---------- profile (01 §4.9) ----------

  /** A new resource with only the profile's client-owned elements, after checking them. */
  static AllergyIntolerance profile(AllergyIntolerance sent) {
    AllergyIntolerance a = new AllergyIntolerance();
    CodeableConcept status = sent.getClinicalStatus();
    if (status.getCoding().size() != 1
        || !CLINICAL.equals(status.getCodingFirstRep().getSystem())
        || !STATUSES.contains(status.getCodingFirstRep().getCode())) {
      throw invalid("AllergyIntolerance.clinicalStatus must be active or inactive.");
    }
    a.setClinicalStatus(
        new CodeableConcept()
            .addCoding(new Coding(CLINICAL, status.getCodingFirstRep().getCode(), null)));
    a.setCode(
        new CodeableConcept()
            .setText(text(sent.getCode().getText(), "AllergyIntolerance.code.text")));
    if (sent.hasCriticality()) {
      if (sent.getCriticality() == AllergyIntoleranceCriticality.NULL) {
        throw invalid("AllergyIntolerance.criticality must be low, high or unable-to-assess.");
      }
      a.setCriticality(sent.getCriticality());
    }
    List<AllergyIntoleranceReactionComponent> reactions = new ArrayList<>();
    for (AllergyIntoleranceReactionComponent r : sent.getReaction()) {
      AllergyIntoleranceReactionComponent clean = new AllergyIntoleranceReactionComponent();
      if (!r.hasManifestation()) {
        throw invalid("AllergyIntolerance.reaction.manifestation is required.");
      }
      for (CodeableConcept m : r.getManifestation()) {
        clean.addManifestation(
            new CodeableConcept()
                .setText(text(m.getText(), "AllergyIntolerance.reaction.manifestation.text")));
      }
      reactions.add(clean);
    }
    a.setReaction(reactions);
    return a;
  }

  private static String patient(AllergyIntolerance a) {
    String ref = a.getPatient().getReference();
    if (ref == null || !ref.matches("^Patient/[A-Za-z0-9-]{1,64}$")) {
      throw invalid("AllergyIntolerance.patient must reference Patient/{id}.");
    }
    return ref.substring("Patient/".length());
  }

  private static String text(String value, String path) {
    if (value == null || value.isBlank() || value.length() > MAX_TEXT) {
      throw invalid(path + " must be 1 to " + MAX_TEXT + " characters.");
    }
    return value;
  }

  /** 03 §2.2 search fields of {@code allergy_intolerances}. */
  static Document search(AllergyIntolerance a) {
    return new Document("clinicalStatus", a.getClinicalStatus().getCodingFirstRep().getCode())
        .append("criticality", a.hasCriticality() ? a.getCriticality().toCode() : null)
        .append("recordedDate", a.getRecordedDate());
  }

  static Document toDocument(AllergyIntolerance a) {
    AllergyIntolerance copy = a.copy();
    copy.setMeta(null);
    return Document.parse(FhirJson.serialize(copy));
  }

  private static View view(Allergies.Stored s) {
    AllergyIntolerance a = FhirJson.parse(s.resource().toJson(), AllergyIntolerance.class);
    a.setId(s.id());
    a.getMeta().setVersionId(Integer.toString(s.version()));
    return new View(a, s.version());
  }

  private static ShpException invalid(String detail) {
    return new ShpException("validation-failed", detail);
  }
}
