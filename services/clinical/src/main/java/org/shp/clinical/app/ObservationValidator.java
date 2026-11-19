package org.shp.clinical.app;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationComponentComponent;
import org.hl7.fhir.r4.model.Observation.ObservationReferenceRangeComponent;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.shp.clinical.app.VitalSigns.Vital;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Component;

/**
 * The Observation profile of 01 §4.7 and the value rules of 09 C16: LOINC code (vital-signs value
 * set, or any LOINC for a laboratory test), UCUM unit of the table, plausible range, blood pressure
 * as exactly two components. On success the server-owned {@code interpretation} (H, L, N) is set
 * from the {@code referenceRange} of the resource, or the table's adult range for a vital sign;
 * whatever the client sent there is replaced.
 *
 * <p>Errors: {@code validation-failed} (400) for the structure, {@link ValueOutOfRangeException}
 * (422) for an implausible value. Details name the element path, never a value.
 */
@Component
public class ObservationValidator {
  public static final String CATEGORY_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/observation-category";
  public static final String INTERPRETATION_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";
  public static final String VITAL_SIGNS = "vital-signs";
  public static final String LABORATORY = "laboratory";

  private static final Set<ObservationStatus> STATUSES =
      Set.of(ObservationStatus.FINAL, ObservationStatus.AMENDED, ObservationStatus.ENTEREDINERROR);
  private static final Pattern LOINC_CODE = Pattern.compile("^\\d{1,7}-\\d$");
  private static final String ID = "[A-Za-z0-9-]{1,64}";

  private final ObservationCodeValidator codes;

  public ObservationValidator(ObservationCodeValidator codes) {
    this.codes = codes;
  }

  /** Validates {@code o} and sets its {@code interpretation}. */
  public void validate(Observation o) {
    if (!o.hasStatus() || !STATUSES.contains(o.getStatus())) {
      throw invalid("Observation.status must be final, amended or entered-in-error.");
    }
    String category = category(o);
    String code = loinc(o.getCode(), "Observation.code");
    reference(o.getSubject(), "Patient", "Observation.subject", true);
    reference(o.getEncounter(), "Encounter", "Observation.encounter", VITAL_SIGNS.equals(category));
    if (!o.hasEffectiveDateTimeType() || o.getEffectiveDateTimeType().getValue() == null) {
      throw invalid("Observation.effectiveDateTime is required.");
    }
    if (o.getPerformer().size() != 1) {
      throw invalid("Observation.performer must have exactly one entry.");
    }
    reference(o.getPerformer().get(0), "Practitioner", "Observation.performer", true);
    if (o.getDerivedFrom().size() > 1) {
      throw invalid("Observation.derivedFrom must have at most one entry.");
    }
    if (o.hasDerivedFrom()) {
      reference(o.getDerivedFrom().get(0), "DocumentReference", "Observation.derivedFrom", true);
    }
    if (o.getReferenceRange().size() > 1) {
      throw invalid("Observation.referenceRange must have at most one entry.");
    }

    if (VITAL_SIGNS.equals(category)) {
      codes.validate(code);
      if (VitalSigns.BLOOD_PRESSURE_PANEL.equals(code)) {
        bloodPressure(o);
      } else {
        vital(o, code);
      }
    } else {
      laboratory(o);
    }
  }

  // ---------- vital signs ----------

  private static void vital(Observation o, String code) {
    if (o.hasComponent()) {
      throw invalid("Observation.component is allowed only for blood pressure.");
    }
    Vital vital = VitalSigns.measure(code).orElseThrow();
    BigDecimal value = quantity(o.getValue(), vital, "Observation.valueQuantity");
    o.setInterpretation(interpretation(value, rangeOf(o), vital));
  }

  /** 85354-9: no value of its own, exactly one systolic and one diastolic component. */
  private static void bloodPressure(Observation o) {
    if (o.hasValue()) {
      throw invalid("Observation.value[x] is not allowed for blood pressure; use component.");
    }
    List<ObservationComponentComponent> components = o.getComponent();
    if (components.size() != 2) {
      throw invalid("Observation.component must have exactly 2 entries for blood pressure.");
    }
    List<String> seen = new ArrayList<>();
    String worst = null;
    for (ObservationComponentComponent c : components) {
      String code = loinc(c.getCode(), "Observation.component.code");
      if (!VitalSigns.SYSTOLIC.equals(code) && !VitalSigns.DIASTOLIC.equals(code)) {
        throw invalid("Observation.component.code must be systolic or diastolic.");
      }
      if (seen.contains(code)) {
        throw invalid("Observation.component.code must not repeat.");
      }
      seen.add(code);
      String name = VitalSigns.SYSTOLIC.equals(code) ? "systolic" : "diastolic";
      Vital vital = VitalSigns.measure(code).orElseThrow();
      BigDecimal value =
          quantity(c.getValue(), vital, "Observation.component[" + name + "].valueQuantity");
      List<CodeableConcept> flag =
          interpretation(value, rangeOf(c.getReferenceRange(), name), vital);
      c.setInterpretation(flag);
      worst = worse(worst, flag.isEmpty() ? null : flag.get(0).getCodingFirstRep().getCode());
    }
    o.setInterpretation(worst == null ? List.of() : List.of(flag(worst)));
  }

  // ---------- laboratory ----------

  /** Free LOINC; a numeric result, when given, must be a UCUM quantity. */
  private static void laboratory(Observation o) {
    if (o.hasComponent()) {
      throw invalid("Observation.component is allowed only for blood pressure.");
    }
    if (!o.hasValue()) {
      o.setInterpretation(List.of());
      return;
    }
    if (!(o.getValue() instanceof Quantity q) || q.getValue() == null) {
      throw invalid("Observation.valueQuantity must have a value.");
    }
    if (!VitalSigns.UCUM.equals(q.getSystem()) || q.getCode() == null || q.getCode().isBlank()) {
      throw invalid("Observation.valueQuantity must use a UCUM unit.");
    }
    ObservationReferenceRangeComponent range = rangeOf(o);
    o.setInterpretation(
        range == null ? List.of() : List.of(flag(compare(q.getValue(), low(range), high(range)))));
  }

  // ---------- helpers ----------

  private static String category(Observation o) {
    if (o.getCategory().size() != 1 || o.getCategoryFirstRep().getCoding().size() != 1) {
      throw invalid("Observation.category must have exactly one coding.");
    }
    Coding c = o.getCategoryFirstRep().getCodingFirstRep();
    if (!CATEGORY_SYSTEM.equals(c.getSystem())
        || !(VITAL_SIGNS.equals(c.getCode()) || LABORATORY.equals(c.getCode()))) {
      throw invalid("Observation.category must be vital-signs or laboratory.");
    }
    return c.getCode();
  }

  private static String loinc(CodeableConcept concept, String path) {
    if (concept.getCoding().size() != 1) {
      throw invalid(path + " must have exactly one coding.");
    }
    Coding c = concept.getCodingFirstRep();
    if (!VitalSigns.LOINC.equals(c.getSystem())
        || c.getCode() == null
        || !LOINC_CODE.matcher(c.getCode()).matches()) {
      throw invalid(path + " must be a LOINC code.");
    }
    return c.getCode();
  }

  private static void reference(Reference r, String type, String path, boolean required) {
    if (r == null || r.isEmpty()) {
      if (required) {
        throw invalid(path + " is required.");
      }
      return;
    }
    String ref = r.getReference();
    if (ref == null || !ref.matches("^" + type + "/" + ID + "$")) {
      throw invalid(path + " must reference " + type + "/{id}.");
    }
  }

  /** The value of a vital sign: a UCUM quantity in the table's unit, within the plausible range. */
  private static BigDecimal quantity(Object value, Vital vital, String path) {
    if (!(value instanceof Quantity q) || q.getValue() == null) {
      throw invalid(path + " is required.");
    }
    if (!VitalSigns.UCUM.equals(q.getSystem()) || !vital.unit().equals(q.getCode())) {
      throw invalid(path + ".code must be the UCUM unit " + vital.unit() + ".");
    }
    if (!vital.plausible(q.getValue())) {
      throw new ValueOutOfRangeException(path + ".value");
    }
    return q.getValue();
  }

  private static ObservationReferenceRangeComponent rangeOf(Observation o) {
    return o.getReferenceRange().isEmpty() ? null : o.getReferenceRangeFirstRep();
  }

  private static ObservationReferenceRangeComponent rangeOf(
      List<ObservationReferenceRangeComponent> ranges, String name) {
    if (ranges.size() > 1) {
      throw invalid(
          "Observation.component[" + name + "].referenceRange must have at most one entry.");
    }
    return ranges.isEmpty() ? null : ranges.get(0);
  }

  /** The resource's range when it has one, else the table's; none → no interpretation. */
  private static List<CodeableConcept> interpretation(
      BigDecimal value, ObservationReferenceRangeComponent range, Vital vital) {
    if (range != null && (low(range) != null || high(range) != null)) {
      return List.of(flag(compare(value, low(range), high(range))));
    }
    if (vital.hasReference()) {
      return List.of(flag(compare(value, vital.referenceLow(), vital.referenceHigh())));
    }
    return List.of();
  }

  private static BigDecimal low(ObservationReferenceRangeComponent r) {
    return r.hasLow() ? r.getLow().getValue() : null;
  }

  private static BigDecimal high(ObservationReferenceRangeComponent r) {
    return r.hasHigh() ? r.getHigh().getValue() : null;
  }

  static String compare(BigDecimal value, BigDecimal low, BigDecimal high) {
    if (low != null && value.compareTo(low) < 0) {
      return "L";
    }
    if (high != null && value.compareTo(high) > 0) {
      return "H";
    }
    return "N";
  }

  /** H or L outranks N; with both H and L present, H is reported. */
  static String worse(String a, String b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    if ("H".equals(a) || "H".equals(b)) {
      return "H";
    }
    if ("L".equals(a) || "L".equals(b)) {
      return "L";
    }
    return "N";
  }

  private static CodeableConcept flag(String code) {
    return new CodeableConcept().addCoding(new Coding(INTERPRETATION_SYSTEM, code, null));
  }

  private static ShpException invalid(String detail) {
    return new ShpException("validation-failed", detail);
  }
}
