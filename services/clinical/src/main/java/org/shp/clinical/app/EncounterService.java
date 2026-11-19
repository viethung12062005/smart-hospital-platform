package org.shp.clinical.app;

import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.DiagnosisComponent;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MarkdownType;
import org.hl7.fhir.r4.model.Reference;
import org.shp.clinical.app.Encounters.Stored;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * The Encounter API (09 C11–C13; 01 §4.6): {@code $start}, read, list by patient, and editing the
 * reason, clinical note and diagnoses before the visit ends. The Encounter itself is created from
 * the check-in event (CLN-14); authorization by care is done by the matrix before these run.
 */
@Service
public class EncounterService {
  public static final String CLINICAL_NOTE =
      "https://shp.example.org/fhir/StructureDefinition/clinical-note";
  public static final String ADDENDUM = "https://shp.example.org/fhir/StructureDefinition/addendum";

  static final String ARRIVED = "arrived";
  static final String IN_PROGRESS = "in-progress";
  static final String FINISHED = "finished";

  /** An Encounter as returned to the client, with the version for {@code ETag}. */
  public record View(Encounter resource, int version) {}

  private final Encounters encounters;

  public EncounterService(Encounters encounters) {
    this.encounters = encounters;
  }

  /** C12 read. */
  public View read(String id) {
    return view(load(id));
  }

  /** C12 search by patient, newest first; {@code finishedOnly} for a patient (15 §3). */
  public List<Encounter> byPatient(String patientId, boolean finishedOnly, int count) {
    return encounters.byPatient(patientId, finishedOnly ? FINISHED : null, count).stream()
        .map(s -> view(s).resource())
        .toList();
  }

  /**
   * C11: {@code arrived → in-progress}, by the appointment's doctor only (US-024 AC-1, AC-2). The
   * attender was set from the appointment at check-in.
   *
   * @param practitionerId the caller's {@code Practitioner} id, {@code null} for any other caller
   */
  public View start(String id, String practitionerId, String changedBy) {
    Stored current = load(id);
    Encounter e = parse(current.resource());
    if (practitionerId == null || !practitionerId.equals(attender(e))) {
      throw new ShpException("forbidden", "Only the appointment's doctor can start the visit.");
    }
    if (!ARRIVED.equals(e.getStatus().toCode())) {
      throw new ShpException("invalid-state-transition", "Encounter.status must be arrived.");
    }
    e.setStatus(Encounter.EncounterStatus.INPROGRESS);
    Document search = new Document(current.search()).append("status", IN_PROGRESS);
    return view(
        encounters.update(current, toDocument(e), search, current.version(), changedBy, null));
  }

  /**
   * C13: only {@code reasonCode}, {@code extension[clinical-note]} and {@code diagnosis} change;
   * every other element keeps its stored value whatever the body says (the server owns them). A
   * finished visit → 409 {@code invalid-state-transition} (US-024 AC-3, AC-04.2); use {@code
   * $addendum}.
   */
  public View updateNote(String id, String body, int expectedVersion, String changedBy) {
    Encounter sent = FhirJson.parse(body, Encounter.class);
    if (sent.hasIdElement() && !id.equals(sent.getIdElement().getIdPart())) {
      throw invalid("Encounter.id must match the URL.");
    }
    Stored current = load(id);
    Encounter e = parse(current.resource());
    if (FINISHED.equals(e.getStatus().toCode())) {
      throw new ShpException(
          "invalid-state-transition", "A finished Encounter cannot be edited; use $addendum.");
    }
    e.setReasonCode(reasons(sent));
    e.setDiagnosis(diagnoses(sent));
    List<Extension> kept = new ArrayList<>();
    for (Extension x : e.getExtension()) {
      if (!CLINICAL_NOTE.equals(x.getUrl())) {
        kept.add(x); // addenda and other server-owned extensions stay as stored
      }
    }
    note(sent).ifPresent(kept::add);
    e.setExtension(kept);
    return view(
        encounters.update(
            current, toDocument(e), current.search(), expectedVersion, changedBy, null));
  }

  // ---------- body rules ----------

  /** {@code reasonCode[].text} only (01 §4.6). */
  private static List<CodeableConcept> reasons(Encounter sent) {
    List<CodeableConcept> out = new ArrayList<>();
    for (CodeableConcept c : sent.getReasonCode()) {
      if (c.hasCoding() || !c.hasText() || c.getText().isBlank()) {
        throw invalid("Encounter.reasonCode must carry text only.");
      }
      if (c.getText().length() > 500) {
        throw invalid("Encounter.reasonCode.text must be at most 500 characters.");
      }
      out.add(new CodeableConcept().setText(c.getText()));
    }
    return out;
  }

  /** {@code diagnosis[].condition} = {@code Condition/{id}} (01 §4.6). */
  private static List<DiagnosisComponent> diagnoses(Encounter sent) {
    List<DiagnosisComponent> out = new ArrayList<>();
    for (DiagnosisComponent d : sent.getDiagnosis()) {
      String ref = d.getCondition().getReference();
      if (ref == null || !ref.matches("^Condition/[A-Za-z0-9-]{1,64}$")) {
        throw invalid("Encounter.diagnosis.condition must reference Condition/{id}.");
      }
      out.add(new DiagnosisComponent().setCondition(new Reference(ref)));
    }
    return out;
  }

  /** At most one {@code extension[clinical-note]} with a {@code valueMarkdown}. */
  private static java.util.Optional<Extension> note(Encounter sent) {
    Extension found = null;
    for (Extension x : sent.getExtension()) {
      if (ADDENDUM.equals(x.getUrl())) {
        continue; // addenda are added through $addendum only; the stored ones are kept
      }
      if (!CLINICAL_NOTE.equals(x.getUrl())) {
        throw invalid("Encounter.extension may only be clinical-note.");
      }
      if (found != null) {
        throw invalid("Encounter.extension[clinical-note] must appear at most once.");
      }
      if (!(x.getValue() instanceof MarkdownType md) || md.getValue() == null) {
        throw invalid("Encounter.extension[clinical-note] must have a valueMarkdown.");
      }
      if (md.getValue().length() > 20_000) {
        throw invalid("Encounter.extension[clinical-note] must be at most 20000 characters.");
      }
      found = new Extension(CLINICAL_NOTE, new MarkdownType(md.getValue()));
    }
    return java.util.Optional.ofNullable(found);
  }

  // ---------- helpers ----------

  private Stored load(String id) {
    return encounters.find(id).orElseThrow(() -> new ShpException("not-found"));
  }

  /** The {@code Practitioner} id of the attender participant, or {@code null}. */
  public static String attender(Encounter e) {
    return e.getParticipant().stream()
        .map(p -> p.getIndividual().getReference())
        .filter(r -> r != null && r.startsWith("Practitioner/"))
        .map(r -> r.substring("Practitioner/".length()))
        .findFirst()
        .orElse(null);
  }

  static Encounter parse(Document resource) {
    return FhirJson.parse(resource.toJson(), Encounter.class);
  }

  static Document toDocument(Encounter e) {
    return Document.parse(FhirJson.serialize(e));
  }

  static View view(Stored s) {
    Encounter e = parse(s.resource());
    e.setId(s.id());
    e.getMeta().setVersionId(Integer.toString(s.version()));
    return new View(e, s.version());
  }

  private static ShpException invalid(String detail) {
    return new ShpException("validation-failed", detail);
  }
}
