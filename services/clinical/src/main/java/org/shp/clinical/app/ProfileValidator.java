package org.shp.clinical.app;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointUse;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Patient.LinkType;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.PrimitiveType;
import org.hl7.fhir.r4.model.Property;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.shp.clinical.infra.TerminologyService;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Component;

/**
 * Write-side profile of Patient, Practitioner and PractitionerRole (01 §4.1, §4.2). Only the listed
 * elements are accepted (anything else, at any depth, is rejected), cardinalities hold and coded
 * values belong to the shared value sets. A violation → 400 {@code validation-failed}; the detail
 * names the element path, never the submitted value, which may be PII.
 *
 * <p>Validates the resource as it will be stored: callers fill server-generated elements (MRN,
 * {@code active}) before calling. FHIR syntax (types, enum codes, ids) is already enforced by
 * {@code FhirJson}; this class narrows valid FHIR to the SHP profile.
 */
@Component
public class ProfileValidator {
  static final String MRN_SYSTEM = "urn:shp:mrn";
  static final String NATIONAL_ID_SYSTEM = "urn:shp:cccd";
  static final String STAFF_SYSTEM = "urn:shp:practitioner";
  static final String LANGUAGE_SYSTEM = "urn:ietf:bcp:47";
  static final String SPECIALTY_SYSTEM = "https://shp.example.org/fhir/CodeSystem/specialty";
  static final String ROLE_SYSTEM = "https://shp.example.org/fhir/CodeSystem/practitioner-role";

  /** Birth dates are local calendar dates (01 §4.1: not in the future, age at most 120). */
  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private static final int MAX_AGE_YEARS = 120;
  private static final Set<String> LANGUAGES = Set.of("vi", "en");
  private static final Pattern MRN = Pattern.compile("BN-\\d{4}-\\d{6}");
  private static final Pattern NATIONAL_ID = Pattern.compile("\\d{12}");
  private static final Pattern STAFF_CODE = Pattern.compile("NV-\\d{4}");
  private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
  private static final Pattern PATIENT_REF = Pattern.compile("Patient/[A-Za-z0-9\\-.]{1,64}");
  private static final Pattern PRACTITIONER_REF =
      Pattern.compile("Practitioner/[A-Za-z0-9\\-.]{1,64}");

  private final TerminologyService terminology;
  private final Clock clock;

  public ProfileValidator(TerminologyService terminology, Clock clock) {
    this.terminology = terminology;
    this.clock = clock;
  }

  // ---------- Patient (01 §4.1) ----------

  public void validate(Patient p) {
    allowOnly(
        p,
        "Patient",
        "id",
        "meta",
        "identifier",
        "active",
        "name",
        "telecom",
        "gender",
        "birthDate",
        "address",
        "contact",
        "communication",
        "link");
    meta(p, "Patient");
    patientIdentifiers(p.getIdentifier());
    require(p.hasActive(), "Patient.active is required.");
    patientName(p.getName());
    patientTelecom(p.getTelecom());
    require(p.hasGender(), "Patient.gender is required.");
    birthDate(p);
    atMostOne(p.getAddress(), "Patient.address");
    p.getAddress().forEach(a -> allowOnly(a, "Patient.address", "text", "city", "district"));
    atMostOne(p.getContact(), "Patient.contact");
    p.getContact().forEach(this::contact);
    atMostOne(p.getCommunication(), "Patient.communication");
    p.getCommunication().forEach(this::communication);
    p.getLink().forEach(this::link);
  }

  private void patientIdentifiers(List<Identifier> identifiers) {
    int mrn = 0;
    int nationalId = 0;
    for (Identifier id : identifiers) {
      allowOnly(id, "Patient.identifier", "system", "value");
      if (MRN_SYSTEM.equals(id.getSystem())) {
        mrn++;
        require(matches(MRN, id.getValue()), "Patient.identifier[mrn] must be BN-YYYY-NNNNNN.");
      } else if (NATIONAL_ID_SYSTEM.equals(id.getSystem())) {
        nationalId++;
        require(matches(NATIONAL_ID, id.getValue()), "Patient.identifier[cccd] must be 12 digits.");
      } else {
        throw invalid("Patient.identifier.system is not allowed.");
      }
    }
    require(mrn == 1, "Patient.identifier[mrn] is required exactly once.");
    require(nationalId <= 1, "Patient.identifier[cccd] is allowed at most once.");
  }

  private void patientName(List<HumanName> names) {
    exactlyOne(names, "Patient.name");
    HumanName name = names.getFirst();
    allowOnly(name, "Patient.name", "use", "text", "family", "given");
    require(name.getUse() == NameUse.OFFICIAL, "Patient.name.use must be official.");
    nameText(name, "Patient.name.text");
  }

  private void patientTelecom(List<ContactPoint> telecom) {
    int phones = 0;
    int emails = 0;
    for (ContactPoint t : telecom) {
      allowOnly(t, "Patient.telecom", "system", "value");
      if (t.getSystem() == ContactPointSystem.PHONE) {
        phones++;
        require(matches(E164, t.getValue()), "Patient.telecom[phone] must be E.164.");
      } else if (t.getSystem() == ContactPointSystem.EMAIL) {
        emails++;
        require(matches(EMAIL, t.getValue()), "Patient.telecom[email] is not a valid email.");
      } else {
        throw invalid("Patient.telecom.system must be phone or email.");
      }
    }
    require(phones == 1, "Patient.telecom[phone] is required exactly once.");
    require(emails <= 1, "Patient.telecom[email] is allowed at most once.");
  }

  private void birthDate(Patient p) {
    require(p.hasBirthDate(), "Patient.birthDate is required.");
    require(
        p.getBirthDateElement().getPrecision() == TemporalPrecisionEnum.DAY,
        "Patient.birthDate must be a full date.");
    LocalDate birth = LocalDate.parse(p.getBirthDateElement().getValueAsString());
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    require(!birth.isAfter(today), "Patient.birthDate must not be in the future.");
    require(
        !birth.isBefore(today.minusYears(MAX_AGE_YEARS)),
        "Patient.birthDate must be within the last 120 years.");
  }

  private void contact(Patient.ContactComponent c) {
    allowOnly(c, "Patient.contact", "name", "telecom", "relationship");
    if (c.hasName()) {
      allowOnly(c.getName(), "Patient.contact.name", "text");
    }
    for (ContactPoint t : c.getTelecom()) {
      allowOnly(t, "Patient.contact.telecom", "system", "value");
      require(
          t.getSystem() == ContactPointSystem.PHONE && matches(E164, t.getValue()),
          "Patient.contact.telecom must be an E.164 phone.");
    }
    c.getRelationship().forEach(r -> allowOnly(r, "Patient.contact.relationship", "text"));
  }

  private void communication(Patient.PatientCommunicationComponent c) {
    allowOnly(c, "Patient.communication", "language");
    CodeableConcept language = c.getLanguage();
    allowOnly(language, "Patient.communication.language", "coding");
    exactlyOne(language.getCoding(), "Patient.communication.language.coding");
    Coding coding = language.getCodingFirstRep();
    allowOnly(coding, "Patient.communication.language.coding", "system", "code");
    require(
        LANGUAGE_SYSTEM.equals(coding.getSystem()) && LANGUAGES.contains(coding.getCode()),
        "Patient.communication.language must be vi or en.");
  }

  private void link(Patient.PatientLinkComponent link) {
    allowOnly(link, "Patient.link", "other", "type");
    require(link.getType() == LinkType.REPLACEDBY, "Patient.link.type must be replaced-by.");
    reference(link.getOther(), "Patient.link.other", PATIENT_REF);
  }

  // ---------- Practitioner (01 §4.2) ----------

  public void validate(Practitioner p) {
    allowOnly(
        p,
        "Practitioner",
        "id",
        "meta",
        "identifier",
        "active",
        "name",
        "telecom",
        "gender",
        "qualification");
    meta(p, "Practitioner");
    exactlyOne(p.getIdentifier(), "Practitioner.identifier");
    Identifier id = p.getIdentifierFirstRep();
    allowOnly(id, "Practitioner.identifier", "system", "value");
    require(
        STAFF_SYSTEM.equals(id.getSystem()) && matches(STAFF_CODE, id.getValue()),
        "Practitioner.identifier must be urn:shp:practitioner NV-NNNN.");
    require(p.hasActive(), "Practitioner.active is required.");
    exactlyOne(p.getName(), "Practitioner.name");
    HumanName name = p.getNameFirstRep();
    allowOnly(name, "Practitioner.name", "text", "prefix");
    nameText(name, "Practitioner.name.text");
    atMostOne(p.getTelecom(), "Practitioner.telecom");
    for (ContactPoint t : p.getTelecom()) {
      allowOnly(t, "Practitioner.telecom", "system", "value", "use");
      require(
          t.getSystem() == ContactPointSystem.EMAIL
              && t.getUse() == ContactPointUse.WORK
              && matches(EMAIL, t.getValue()),
          "Practitioner.telecom must be a work email.");
    }
    for (Practitioner.PractitionerQualificationComponent q : p.getQualification()) {
      allowOnly(q, "Practitioner.qualification", "code");
      allowOnly(q.getCode(), "Practitioner.qualification.code", "text");
      require(
          q.getCode().hasText() && !q.getCode().getText().isBlank(),
          "Practitioner.qualification.code.text is required.");
    }
  }

  // ---------- PractitionerRole (01 §4.2) ----------

  public void validate(PractitionerRole r) {
    allowOnly(r, "PractitionerRole", "id", "meta", "practitioner", "active", "specialty", "code");
    meta(r, "PractitionerRole");
    require(r.hasPractitioner(), "PractitionerRole.practitioner is required.");
    reference(r.getPractitioner(), "PractitionerRole.practitioner", PRACTITIONER_REF);
    require(r.hasActive(), "PractitionerRole.active is required.");
    exactlyOne(r.getSpecialty(), "PractitionerRole.specialty");
    coded(r.getSpecialtyFirstRep(), "PractitionerRole.specialty", SPECIALTY_SYSTEM, "specialty");
    exactlyOne(r.getCode(), "PractitionerRole.code");
    coded(r.getCodeFirstRep(), "PractitionerRole.code", ROLE_SYSTEM, "practitioner-role");
  }

  // ---------- shared rules ----------

  /** {@code meta} is server-managed: only {@code versionId} and {@code lastUpdated}. */
  private static void meta(Resource resource, String path) {
    if (resource.hasMeta()) {
      allowOnly(resource.getMeta(), path + ".meta", "versionId", "lastUpdated");
    }
  }

  private static void nameText(HumanName name, String path) {
    String text = name.hasText() ? name.getText().strip() : "";
    require(text.length() >= 2 && text.length() <= 100, path + " must be 2-100 characters.");
  }

  private static void reference(Reference ref, String path, Pattern target) {
    allowOnly(ref, path, "reference");
    require(matches(target, ref.getReference()), path + " references the wrong resource type.");
  }

  /** One coding from {@code system} whose code is in the shared value set {@code valueSet}. */
  private void coded(CodeableConcept concept, String path, String system, String valueSet) {
    allowOnly(concept, path, "coding");
    exactlyOne(concept.getCoding(), path + ".coding");
    Coding coding = concept.getCodingFirstRep();
    allowOnly(coding, path + ".coding", "system", "code", "display");
    require(system.equals(coding.getSystem()), path + ".coding.system is not allowed.");
    require(
        coding.hasCode() && terminology.isKnown(valueSet, coding.getCode()),
        path + ".coding.code is not in the " + valueSet + " value set.");
  }

  /**
   * Rejects any element of {@code element} that carries a value but is not listed (01 §4: fields
   * that are not listed are not used and are rejected on write).
   */
  private static void allowOnly(Base element, String path, String... allowed) {
    Set<String> names = Set.of(allowed);
    for (Property child : element.children()) {
      if (!child.hasValues()) {
        continue;
      }
      if (!names.contains(child.getName())) {
        throw invalid(path + "." + child.getName() + " is not allowed.");
      }
      for (Base value : child.getValues()) {
        // FHIR JSON "_field": a primitive may carry an id or extensions of its own.
        if (value instanceof PrimitiveType<?> primitive
            && (primitive.hasExtension() || primitive.hasId())) {
          throw invalid(path + "." + child.getName() + ".extension is not allowed.");
        }
      }
    }
  }

  private static void exactlyOne(List<?> items, String path) {
    require(items.size() == 1, path + " is required exactly once.");
  }

  private static void atMostOne(List<?> items, String path) {
    require(items.size() <= 1, path + " is allowed at most once.");
  }

  private static boolean matches(Pattern pattern, String value) {
    return value != null && pattern.matcher(value).matches();
  }

  private static void require(boolean condition, String detail) {
    if (!condition) {
      throw invalid(detail);
    }
  }

  private static ShpException invalid(String detail) {
    return new ShpException("validation-failed", detail);
  }
}
