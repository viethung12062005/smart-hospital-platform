package org.shp.clinical.app;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Reference;
import org.shp.clinical.infra.TerminologyService;
import org.shp.events.Uuids;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ErrorInfo;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * The Condition API (09 C17; 01 §4.8; US-026): a doctor with care records an ICD-10 diagnosis or
 * underlying condition; a wrong one is never deleted, it is marked {@code entered-in-error}. {@code
 * recorder} and {@code recordedDate} are the server's.
 */
@Service
public class ConditionService {
  public static final String ICD10 = "http://hl7.org/fhir/sid/icd-10";
  public static final String CLINICAL = "http://terminology.hl7.org/CodeSystem/condition-clinical";
  public static final String VERIFICATION =
      "http://terminology.hl7.org/CodeSystem/condition-ver-status";
  public static final String CATEGORY = "http://terminology.hl7.org/CodeSystem/condition-category";
  public static final String ENCOUNTER_DIAGNOSIS = "encounter-diagnosis";
  public static final String PROBLEM_LIST_ITEM = "problem-list-item";
  public static final String ENTERED_IN_ERROR = "entered-in-error";

  /** US-026 AC-3: a code outside the loaded ICD-10 table (not yet in the shared catalog, 07 §5). */
  public static final ErrorInfo INVALID_CODE =
      new ErrorInfo(
          "invalid-code", 422, "business-rule", "Mã không hợp lệ.", "The code is not valid.");

  /** Thrown for {@link #INVALID_CODE}; the message names the field, never the code. */
  public static class InvalidCodeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidCodeException(String path) {
      super(path + " is not in the ICD-10 table.");
    }
  }

  static final Set<String> CLINICAL_STATUSES = Set.of("active", "resolved");
  static final Set<String> NEW_VERIFICATIONS = Set.of("provisional", "confirmed");
  static final Set<String> CATEGORIES = Set.of(ENCOUNTER_DIAGNOSIS, PROBLEM_LIST_ITEM);

  public record View(Condition resource, int version) {}

  private final Conditions conditions;
  private final Encounters encounters;
  private final TerminologyService terminology;
  private final Clock clock;

  public ConditionService(
      Conditions conditions, Encounters encounters, TerminologyService terminology, Clock clock) {
    this.conditions = conditions;
    this.encounters = encounters;
    this.terminology = terminology;
    this.clock = clock;
  }

  /** C17 create, by the doctor the matrix allowed (care, AC-04.1); recorder = that doctor. */
  public View create(String body, String practitionerId, String changedBy) {
    if (practitionerId == null) {
      throw new ShpException("forbidden", "Only a doctor can record a Condition.");
    }
    Condition c = FhirJson.parse(body, Condition.class);
    c.setId((String) null);
    c.setMeta(null);
    code(c, "clinicalStatus", c.getClinicalStatus(), CLINICAL, CLINICAL_STATUSES);
    code(c, "verificationStatus", c.getVerificationStatus(), VERIFICATION, NEW_VERIFICATIONS);
    if (c.getCategory().size() != 1) {
      throw invalid("Condition.category must have exactly one entry.");
    }
    String category = code(c, "category", c.getCategoryFirstRep(), CATEGORY, CATEGORIES);
    String icd = icd10(c);
    String patientId = reference(c.getSubject(), "Patient", "Condition.subject", true);
    String encounterId =
        reference(
            c.getEncounter(),
            "Encounter",
            "Condition.encounter",
            ENCOUNTER_DIAGNOSIS.equals(category));
    if (encounterId != null) {
      var e =
          encounters
              .find(encounterId)
              .orElseThrow(() -> invalid("Condition.encounter does not exist."));
      if (!patientId.equals(e.patientId())) {
        throw invalid("Condition.encounter belongs to another patient.");
      }
      if ("finished".equals(e.search().getString("status"))) {
        throw new ShpException(
            "invalid-state-transition", "The Encounter is finished; nothing can be added.");
      }
    }
    for (Annotation n : c.getNote()) {
      if (!n.hasText() || n.getText().isBlank() || n.getText().length() > 2_000) {
        throw invalid("Condition.note.text must be 1 to 2000 characters.");
      }
    }
    String id = Uuids.v7(clock).toString();
    c.setId(id);
    c.setRecorder(new Reference("Practitioner/" + practitionerId));
    // Seconds in UTC: setRecordedDate(Date) would keep only the day.
    c.setRecordedDateElement(
        new DateTimeType(
            Date.from(clock.instant()),
            TemporalPrecisionEnum.SECOND,
            java.util.TimeZone.getTimeZone("UTC")));
    c.setAsserter(null);
    var stored =
        conditions.create(
            id, patientId, toDocument(c), search(c, encounterId, category, icd), changedBy);
    return view(stored);
  }

  /**
   * C17 update: only {@code clinicalStatus}, {@code verificationStatus} and {@code note} change.
   * {@code entered-in-error} keeps the record (US-026 AC-4) and is final: a later change is 409.
   */
  public View update(String id, String body, int expectedVersion, String changedBy) {
    Condition sent = FhirJson.parse(body, Condition.class);
    if (sent.hasIdElement() && !id.equals(sent.getIdElement().getIdPart())) {
      throw invalid("Condition.id must match the URL.");
    }
    var current = conditions.find(id).orElseThrow(() -> new ShpException("not-found"));
    Condition c = FhirJson.parse(current.resource().toJson(), Condition.class);
    if (ENTERED_IN_ERROR.equals(c.getVerificationStatus().getCodingFirstRep().getCode())) {
      throw new ShpException(
          "invalid-state-transition", "A Condition entered in error cannot be changed.");
    }
    code(sent, "clinicalStatus", sent.getClinicalStatus(), CLINICAL, CLINICAL_STATUSES);
    Set<String> verifications = Set.of("provisional", "confirmed", ENTERED_IN_ERROR);
    code(sent, "verificationStatus", sent.getVerificationStatus(), VERIFICATION, verifications);
    for (Annotation n : sent.getNote()) {
      if (!n.hasText() || n.getText().isBlank() || n.getText().length() > 2_000) {
        throw invalid("Condition.note.text must be 1 to 2000 characters.");
      }
    }
    c.setClinicalStatus(sent.getClinicalStatus());
    c.setVerificationStatus(sent.getVerificationStatus());
    c.setNote(sent.getNote());
    Document search =
        new Document(current.search())
            .append("clinicalStatus", c.getClinicalStatus().getCodingFirstRep().getCode())
            .append("verificationStatus", c.getVerificationStatus().getCodingFirstRep().getCode());
    return view(conditions.update(current, toDocument(c), search, expectedVersion, changedBy));
  }

  public View read(String id) {
    return view(conditions.find(id).orElseThrow(() -> new ShpException("not-found")));
  }

  /** C17 list by patient, newest first; a Patient sees only diagnoses of finished visits. */
  public List<Condition> byPatient(String patientId, boolean finishedVisitsOnly, int count) {
    var finished =
        finishedVisitsOnly
            ? encounters.byPatient(patientId, "finished", 1_000).stream()
                .map(Encounters.Stored::id)
                .collect(java.util.stream.Collectors.toSet())
            : null;
    return conditions.byPatient(patientId, finishedVisitsOnly ? 1_000 : count).stream()
        .filter(s -> finished == null || finished.contains(s.search().getString("encounterId")))
        .limit(count)
        .map(s -> view(s).resource())
        .toList();
  }

  // ---------- rules ----------

  private String icd10(Condition c) {
    if (c.getCode().getCoding().size() != 1) {
      throw invalid("Condition.code must have exactly one coding.");
    }
    Coding coding = c.getCode().getCodingFirstRep();
    if (!ICD10.equals(coding.getSystem()) || coding.getCode() == null) {
      throw invalid("Condition.code must be an ICD-10 code.");
    }
    if (!terminology.isKnown("icd10-subset", coding.getCode())) {
      throw new InvalidCodeException("Condition.code");
    }
    return coding.getCode();
  }

  private static String code(
      Condition c, String element, CodeableConcept cc, String system, Set<String> allowed) {
    if (cc == null || cc.getCoding().size() != 1) {
      throw invalid("Condition." + element + " must have exactly one coding.");
    }
    Coding coding = cc.getCodingFirstRep();
    if (!system.equals(coding.getSystem()) || !allowed.contains(coding.getCode())) {
      throw invalid(
          "Condition."
              + element
              + " must be one of "
              + String.join(", ", allowed.stream().sorted().toList())
              + ".");
    }
    return coding.getCode();
  }

  /** The id of a {@code type/{id}} reference; {@code null} when absent and not required. */
  private static String reference(Reference r, String type, String path, boolean required) {
    if (r == null || r.isEmpty()) {
      if (required) {
        throw invalid(path + " is required.");
      }
      return null;
    }
    String ref = r.getReference();
    if (ref == null || !ref.matches("^" + type + "/[A-Za-z0-9-]{1,64}$")) {
      throw invalid(path + " must reference " + type + "/{id}.");
    }
    return ref.substring(type.length() + 1);
  }

  static Document search(Condition c, String encounterId, String category, String code) {
    return new Document("encounterId", encounterId)
        .append("category", category)
        .append("code", code)
        .append("clinicalStatus", c.getClinicalStatus().getCodingFirstRep().getCode())
        .append("verificationStatus", c.getVerificationStatus().getCodingFirstRep().getCode())
        .append("recordedDate", c.getRecordedDate());
  }

  static Document toDocument(Condition c) {
    Condition copy = c.copy();
    copy.setMeta(null);
    return Document.parse(FhirJson.serialize(copy));
  }

  private static View view(Conditions.Stored s) {
    Condition c = FhirJson.parse(s.resource().toJson(), Condition.class);
    c.setId(s.id());
    c.getMeta().setVersionId(Integer.toString(s.version()));
    return new View(c, s.version());
  }

  private static ShpException invalid(String detail) {
    return new ShpException("validation-failed", detail);
  }
}
