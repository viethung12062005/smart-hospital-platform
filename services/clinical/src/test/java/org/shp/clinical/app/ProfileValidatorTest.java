package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointUse;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Patient.LinkType;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.shp.clinical.infra.TerminologyService;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;

/**
 * CLN-02: one test per profile constraint of 01 §4.1 (Patient) and §4.2 (Practitioner,
 * PractitionerRole). Each case starts from a valid resource and breaks exactly one rule. Fake data
 * only.
 */
class ProfileValidatorTest {
  /** 2026-11-17 in Vietnam (UTC+7). */
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZoneOffset.UTC);

  static final String PATIENT =
      """
      {
        "resourceType": "Patient",
        "id": "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01",
        "meta": { "versionId": "2", "lastUpdated": "2026-11-16T09:15:00.000+07:00" },
        "identifier": [
          { "system": "urn:shp:mrn", "value": "BN-2026-000123" },
          { "system": "urn:shp:cccd", "value": "000123456789" }
        ],
        "active": true,
        "name": [ { "use": "official", "text": "Nguyen Van An (test)", "family": "Nguyen", "given": [ "Van", "An" ] } ],
        "telecom": [
          { "system": "phone", "value": "+84900012345" },
          { "system": "email", "value": "an.nguyen@example.test" }
        ],
        "gender": "male",
        "birthDate": "1985-04-12",
        "address": [ { "text": "(fake) Ward 1, District 1", "district": "District 1", "city": "Ho Chi Minh City" } ],
        "contact": [ {
          "relationship": [ { "text": "Wife" } ],
          "name": { "text": "Tran Thi Binh (test)" },
          "telecom": [ { "system": "phone", "value": "+84900067890" } ]
        } ],
        "communication": [ { "language": { "coding": [ { "system": "urn:ietf:bcp:47", "code": "vi" } ] } } ],
        "link": [ { "other": { "reference": "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e09" }, "type": "replaced-by" } ]
      }
      """;

  static final String PRACTITIONER =
      """
      {
        "resourceType": "Practitioner",
        "id": "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02",
        "identifier": [ { "system": "urn:shp:practitioner", "value": "NV-0042" } ],
        "active": true,
        "name": [ { "text": "Dr. Le Minh Chau (test)", "prefix": [ "Dr." ] } ],
        "telecom": [ { "system": "email", "value": "chau.le@example.test", "use": "work" } ],
        "gender": "female",
        "qualification": [ { "code": { "text": "Specialist Level I, Cardiology" } } ]
      }
      """;

  static final String ROLE =
      """
      {
        "resourceType": "PractitionerRole",
        "id": "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03",
        "active": true,
        "practitioner": { "reference": "Practitioner/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02" },
        "code": [ { "coding": [ { "system": "https://shp.example.org/fhir/CodeSystem/practitioner-role", "code": "doctor" } ] } ],
        "specialty": [ { "coding": [ {
          "system": "https://shp.example.org/fhir/CodeSystem/specialty", "code": "cardiology", "display": "Cardiology"
        } ] } ]
      }
      """;

  final ProfileValidator validator = new ProfileValidator(terminology(), CLOCK);

  static TerminologyService terminology() {
    var terminology = mock(TerminologyService.class);
    when(terminology.isKnown(anyString(), anyString())).thenReturn(false);
    when(terminology.isKnown("specialty", "cardiology")).thenReturn(true);
    when(terminology.isKnown("practitioner-role", "doctor")).thenReturn(true);
    when(terminology.isKnown("practitioner-role", "nurse")).thenReturn(true);
    return terminology;
  }

  static Patient patient() {
    return FhirJson.parse(PATIENT, Patient.class);
  }

  static Practitioner practitioner() {
    return FhirJson.parse(PRACTITIONER, Practitioner.class);
  }

  static PractitionerRole role() {
    return FhirJson.parse(ROLE, PractitionerRole.class);
  }

  static void assertRejected(Runnable call, String path) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("validation-failed");
              assertThat(e.info().status()).isEqualTo(400);
              assertThat(e.detail()).startsWith(path);
            });
  }

  // ---------- valid resources ----------
  @Test
  void validResourcesPass() {
    assertThatCode(() -> validator.validate(patient())).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(practitioner())).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(role())).doesNotThrowAnyException();
  }

  @Test
  void minimalPatientWithOnlyRequiredElementsPasses() {
    Patient p = patient();
    p.getIdentifier().removeIf(i -> ProfileValidator.NATIONAL_ID_SYSTEM.equals(i.getSystem()));
    p.getTelecom().removeIf(t -> t.getSystem() == ContactPointSystem.EMAIL);
    p.setAddress(null).setContact(null).setCommunication(null).setLink(null).setMeta(null);
    p.getNameFirstRep().setFamily(null).setGiven(null);
    assertThatCode(() -> validator.validate(p)).doesNotThrowAnyException();
  }

  @Test
  void birthDateBoundariesAreInclusive() {
    Patient today = patient();
    today.setBirthDateElement(new DateType("2026-11-17"));
    assertThatCode(() -> validator.validate(today)).doesNotThrowAnyException();
    Patient oldest = patient();
    oldest.setBirthDateElement(new DateType("1906-11-17"));
    assertThatCode(() -> validator.validate(oldest)).doesNotThrowAnyException();
  }

  @Test
  void rejectionNamesThePathButNeverTheValue() {
    Patient p = patient();
    p.getTelecom().getFirst().setValue("0912345678");
    assertRejected(() -> validator.validate(p), "Patient.telecom[phone]");
    assertThatThrownBy(() -> validator.validate(p)).hasMessageNotContaining("0912345678");
  }

  // ---------- Patient: one case per constraint ----------
  static Stream<Arguments> patientViolations() {
    return Stream.of(
        // out-of-profile elements, at every depth
        bad("Patient.maritalStatus", p -> p.setMaritalStatus(new CodeableConcept().setText("M"))),
        bad(
            "Patient.extension",
            p -> p.addExtension("https://example.test/x", new StringType("x"))),
        bad(
            "Patient.text",
            p -> {
              Narrative text = new Narrative().setStatus(Narrative.NarrativeStatus.GENERATED);
              text.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">x</div>");
              p.setText(text);
            }),
        bad("Patient.meta.tag", p -> p.getMeta().addTag().setCode("x")),
        bad(
            "Patient.identifier.use",
            p -> p.getIdentifierFirstRep().setUse(Identifier.IdentifierUse.OFFICIAL)),
        bad("Patient.name.prefix", p -> p.getNameFirstRep().addPrefix("Mr.")),
        bad("Patient.telecom.use", p -> p.getTelecomFirstRep().setUse(ContactPointUse.HOME)),
        bad("Patient.address.line", p -> p.getAddressFirstRep().addLine("12 Fake Street")),
        bad(
            "Patient.contact.gender",
            p ->
                p.getContactFirstRep()
                    .setGender(org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.FEMALE)),
        bad("Patient.contact.name.family", p -> p.getContactFirstRep().getName().setFamily("Tran")),
        bad(
            "Patient.contact.relationship.coding",
            p -> p.getContactFirstRep().getRelationshipFirstRep().addCoding().setCode("N")),
        bad(
            "Patient.communication.preferred",
            p -> p.getCommunicationFirstRep().setPreferred(true)),
        bad("Patient.link.other.display", p -> p.getLinkFirstRep().getOther().setDisplay("x")),
        // identifier
        bad(
            "Patient.identifier[mrn] is required",
            p ->
                p.getIdentifier().removeIf(i -> ProfileValidator.MRN_SYSTEM.equals(i.getSystem()))),
        bad("Patient.identifier[mrn] must", p -> p.getIdentifierFirstRep().setValue("BN-26-1")),
        bad(
            "Patient.identifier[mrn] is required",
            p ->
                p.addIdentifier()
                    .setSystem(ProfileValidator.MRN_SYSTEM)
                    .setValue("BN-2026-000124")),
        bad(
            "Patient.identifier.system",
            p -> p.addIdentifier().setSystem("urn:other").setValue("x")),
        bad("Patient.identifier[cccd] must", p -> p.getIdentifier().get(1).setValue("00012345678")),
        bad(
            "Patient.identifier[cccd] is allowed",
            p ->
                p.addIdentifier()
                    .setSystem(ProfileValidator.NATIONAL_ID_SYSTEM)
                    .setValue("000123456788")),
        // active, gender
        bad("Patient.active", p -> p.setActiveElement(null)),
        bad("Patient.gender", p -> p.setGender(null)),
        // name
        bad("Patient.name is required", p -> p.setName(null)),
        bad(
            "Patient.name is required",
            p -> p.addName(new HumanName().setUse(NameUse.OFFICIAL).setText("Other (test)"))),
        bad("Patient.name.use", p -> p.getNameFirstRep().setUse(NameUse.USUAL)),
        bad("Patient.name.text", p -> p.getNameFirstRep().setText("A")),
        bad("Patient.name.text", p -> p.getNameFirstRep().setText("A".repeat(101))),
        // telecom
        bad(
            "Patient.telecom[phone] is required",
            p -> p.getTelecom().removeIf(t -> t.getSystem() == ContactPointSystem.PHONE)),
        bad("Patient.telecom[phone] must", p -> p.getTelecomFirstRep().setValue("0912345678")),
        bad("Patient.telecom[phone] is required", p -> p.addTelecom(phone("+84900099999"))),
        bad("Patient.telecom[email] is not", p -> p.getTelecom().get(1).setValue("not-an-email")),
        bad(
            "Patient.telecom[email] is allowed",
            p -> p.addTelecom().setSystem(ContactPointSystem.EMAIL).setValue("b@example.test")),
        bad(
            "Patient.telecom.system",
            p -> p.addTelecom().setSystem(ContactPointSystem.FAX).setValue("+84900011111")),
        // birthDate
        bad("Patient.birthDate is required", p -> p.setBirthDateElement(null)),
        bad(
            "Patient.birthDate must be a full date",
            p -> p.setBirthDateElement(new DateType("1985"))),
        bad("Patient.birthDate must not", p -> p.setBirthDateElement(new DateType("2026-11-18"))),
        bad(
            "Patient.birthDate must be within",
            p -> p.setBirthDateElement(new DateType("1906-11-16"))),
        // 0..1 elements
        bad("Patient.address is allowed", p -> p.addAddress(new Address().setCity("Ha Noi"))),
        bad(
            "Patient.contact is allowed",
            p -> p.addContact().setName(new HumanName().setText("Other (test)"))),
        bad(
            "Patient.contact.telecom",
            p -> p.getContactFirstRep().getTelecomFirstRep().setSystem(ContactPointSystem.EMAIL)),
        bad(
            "Patient.contact.telecom",
            p -> p.getContactFirstRep().getTelecomFirstRep().setValue("0912")),
        bad(
            "Patient.communication is allowed",
            p -> p.addCommunication().setLanguage(language("en"))),
        bad(
            "Patient.communication.language must",
            p -> p.getCommunicationFirstRep().setLanguage(language("fr"))),
        bad(
            "Patient.communication.language must",
            p ->
                p.getCommunicationFirstRep()
                    .getLanguage()
                    .getCodingFirstRep()
                    .setSystem("urn:other")),
        bad(
            "Patient.communication.language.coding is required",
            p ->
                p.getCommunicationFirstRep()
                    .getLanguage()
                    .addCoding(new Coding("urn:ietf:bcp:47", "en", null))),
        // link
        bad("Patient.link.type", p -> p.getLinkFirstRep().setType(LinkType.SEEALSO)),
        bad(
            "Patient.link.other references",
            p -> p.getLinkFirstRep().setOther(new Reference("Practitioner/x"))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("patientViolations")
  void patientViolationIsRejected(String path, Consumer<Patient> breakIt) {
    Patient p = patient();
    breakIt.accept(p);
    assertRejected(() -> validator.validate(p), path);
  }

  // ---------- Practitioner ----------
  static Stream<Arguments> practitionerViolations() {
    return Stream.of(
        badP("Practitioner.birthDate", p -> p.setBirthDateElement(new DateType("1980-01-01"))),
        badP("Practitioner.address", p -> p.addAddress().setCity("Ha Noi")),
        badP("Practitioner.meta.security", p -> p.getMeta().addSecurity().setCode("x")),
        badP("Practitioner.identifier is required", p -> p.setIdentifier(null)),
        badP(
            "Practitioner.identifier is required",
            p -> p.addIdentifier().setSystem(ProfileValidator.STAFF_SYSTEM).setValue("NV-0043")),
        badP("Practitioner.identifier must", p -> p.getIdentifierFirstRep().setValue("NV-42")),
        badP(
            "Practitioner.identifier must",
            p -> p.getIdentifierFirstRep().setSystem(ProfileValidator.MRN_SYSTEM)),
        badP(
            "Practitioner.identifier.use",
            p -> p.getIdentifierFirstRep().setUse(Identifier.IdentifierUse.OFFICIAL)),
        badP("Practitioner.active", p -> p.setActiveElement(null)),
        badP("Practitioner.name is required", p -> p.setName(null)),
        badP("Practitioner.name.family", p -> p.getNameFirstRep().setFamily("Le")),
        badP("Practitioner.name.text", p -> p.getNameFirstRep().setText(" ")),
        badP(
            "Practitioner.telecom is allowed",
            p ->
                p.addTelecom()
                    .setSystem(ContactPointSystem.EMAIL)
                    .setUse(ContactPointUse.WORK)
                    .setValue("b@example.test")),
        badP(
            "Practitioner.telecom must",
            p ->
                p.getTelecomFirstRep()
                    .setSystem(ContactPointSystem.PHONE)
                    .setValue("+84900012345")),
        badP("Practitioner.telecom must", p -> p.getTelecomFirstRep().setUse(ContactPointUse.HOME)),
        badP("Practitioner.telecom must", p -> p.getTelecomFirstRep().setValue("not-an-email")),
        badP(
            "Practitioner.qualification.identifier",
            p -> p.getQualificationFirstRep().addIdentifier().setValue("x")),
        badP(
            "Practitioner.qualification.code.coding",
            p -> p.getQualificationFirstRep().getCode().addCoding().setCode("x")),
        badP(
            "Practitioner.qualification.code.text",
            p -> p.getQualificationFirstRep().getCode().setText(" ")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("practitionerViolations")
  void practitionerViolationIsRejected(String path, Consumer<Practitioner> breakIt) {
    Practitioner p = practitioner();
    breakIt.accept(p);
    assertRejected(() -> validator.validate(p), path);
  }

  @Test
  void practitionerWithoutOptionalElementsPasses() {
    Practitioner p = practitioner();
    p.setTelecom(null).setQualification(null).setGender(null);
    assertThatCode(() -> validator.validate(p)).doesNotThrowAnyException();
  }

  // ---------- PractitionerRole ----------
  static Stream<Arguments> roleViolations() {
    return Stream.of(
        badR("PractitionerRole.location", r -> r.addLocation(new Reference("Location/x"))),
        badR("PractitionerRole.practitioner is required", r -> r.setPractitioner(null)),
        badR(
            "PractitionerRole.practitioner references",
            r -> r.setPractitioner(new Reference("Patient/x"))),
        badR(
            "PractitionerRole.practitioner.display", r -> r.getPractitioner().setDisplay("Dr. Le")),
        badR("PractitionerRole.active", r -> r.setActiveElement(null)),
        badR("PractitionerRole.specialty is required", r -> r.setSpecialty(null)),
        badR(
            "PractitionerRole.specialty is required",
            r -> r.addSpecialty(concept(ProfileValidator.SPECIALTY_SYSTEM, "cardiology"))),
        badR(
            "PractitionerRole.specialty.coding.code",
            r -> r.getSpecialtyFirstRep().getCodingFirstRep().setCode("astrology")),
        badR(
            "PractitionerRole.specialty.coding.code",
            r -> r.getSpecialtyFirstRep().getCodingFirstRep().setCode(null)),
        badR(
            "PractitionerRole.specialty.coding.system",
            r -> r.getSpecialtyFirstRep().getCodingFirstRep().setSystem("http://snomed.info/sct")),
        badR(
            "PractitionerRole.specialty.coding is required",
            r ->
                r.getSpecialtyFirstRep()
                    .addCoding(new Coding(ProfileValidator.SPECIALTY_SYSTEM, "cardiology", null))),
        badR(
            "PractitionerRole.specialty.text", r -> r.getSpecialtyFirstRep().setText("Cardiology")),
        badR(
            "PractitionerRole.specialty.coding.version",
            r -> r.getSpecialtyFirstRep().getCodingFirstRep().setVersion("1")),
        badR("PractitionerRole.code is required", r -> r.setCode(null)),
        badR(
            "PractitionerRole.code.coding.code",
            r -> r.getCodeFirstRep().getCodingFirstRep().setCode("pharmacist")),
        badR(
            "PractitionerRole.code.coding.system",
            r ->
                r.getCodeFirstRep()
                    .getCodingFirstRep()
                    .setSystem(ProfileValidator.SPECIALTY_SYSTEM)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("roleViolations")
  void roleViolationIsRejected(String path, Consumer<PractitionerRole> breakIt) {
    PractitionerRole r = role();
    breakIt.accept(r);
    assertRejected(() -> validator.validate(r), path);
  }

  @Test
  void nurseRolePasses() {
    PractitionerRole r = role();
    r.getCodeFirstRep().getCodingFirstRep().setCode("nurse");
    assertThatCode(() -> validator.validate(r)).doesNotThrowAnyException();
  }

  // ---------- helpers ----------
  static Arguments bad(String path, Consumer<Patient> breakIt) {
    return Arguments.of(path, breakIt);
  }

  static Arguments badP(String path, Consumer<Practitioner> breakIt) {
    return Arguments.of(path, breakIt);
  }

  static Arguments badR(String path, Consumer<PractitionerRole> breakIt) {
    return Arguments.of(path, breakIt);
  }

  static ContactPoint phone(String value) {
    return new ContactPoint().setSystem(ContactPointSystem.PHONE).setValue(value);
  }

  static CodeableConcept language(String code) {
    return new CodeableConcept()
        .addCoding(new Coding(ProfileValidator.LANGUAGE_SYSTEM, code, null));
  }

  static CodeableConcept concept(String system, String code) {
    return new CodeableConcept().addCoding(new Coding(system, code, null));
  }
}
