package org.shp.clinical.app;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.shp.clinical.api.RegistrationRequest;
import org.shp.clinical.api.RegistrationResult;
import org.shp.clinical.domain.PatientNormalizer;
import org.shp.clinical.infra.IdentityLinkRepository;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.web.errors.ShpException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Patient self-registration (C1, 09 §2): validate, refuse a suspected duplicate (AC-01.2), then
 * create the Patient and its {@code identity_links} entry in ONE transaction, so an account never
 * ends up linked to nothing or a record without an owner.
 */
@Service
public class RegistrationService {
  private static final Set<String> GENDERS = Set.of("male", "female", "other", "unknown");
  private static final String LANGUAGE_SYSTEM = "urn:ietf:bcp:47";

  private final PatientRepository patients;
  private final DuplicatePatientCheck duplicates;
  private final IdentityLinkRepository links;

  public RegistrationService(
      PatientRepository patients, DuplicatePatientCheck duplicates, IdentityLinkRepository links) {
    this.patients = patients;
    this.duplicates = duplicates;
    this.links = links;
  }

  /**
   * @param alreadyLinked the token's account already has a fhirUser
   * @throws AlreadyRegisteredException the account has a record (409)
   * @throws ShpException {@code validation-failed} (400) or {@code duplicate-patient-suspected}
   *     (422)
   */
  public RegistrationResult register(
      String subject, String issuer, boolean alreadyLinked, RegistrationRequest request) {
    if (alreadyLinked || links.exists(subject)) {
      throw new AlreadyRegisteredException();
    }
    Patient draft = toPatient(request);
    patients.validateDraft(draft);
    duplicates.requireNoDuplicate(request.phone(), draft.getBirthDateElement().getValueAsString());
    try {
      Entry created =
          patients.create(
              draft,
              subject,
              null,
              entry -> {
                if (links.exists(subject)) {
                  throw new AlreadyRegisteredException();
                }
                links.linkPatient(subject, issuer, entry.id(), subject);
              });
      return new RegistrationResult(created.id(), created.search().getString("mrn"));
    } catch (DuplicateKeyException race) {
      throw new AlreadyRegisteredException(); // the same account registered twice at once
    }
  }

  static Patient toPatient(RegistrationRequest r) {
    if (r == null || r.name() == null) {
      throw invalid("name is required.");
    }
    String family = r.name().family() == null ? "" : r.name().family().strip();
    List<String> given =
        r.name().given() == null
            ? List.of()
            : r.name().given().stream()
                .filter(g -> g != null && !g.isBlank())
                .map(String::strip)
                .toList();
    if (family.isEmpty() || given.isEmpty()) {
      throw invalid("name.family and name.given are required.");
    }
    String phone =
        PatientNormalizer.normalizePhone(r.phone())
            .orElseThrow(() -> invalid("phone is not a valid Vietnamese number."));
    if (r.gender() == null || !GENDERS.contains(r.gender())) {
      throw invalid("gender must be male, female, other or unknown.");
    }
    LocalDate birth;
    try {
      birth = LocalDate.parse(r.birthDate());
    } catch (RuntimeException e) {
      throw invalid("birthDate must be a date (yyyy-MM-dd).");
    }
    String language = r.language() == null ? "vi" : r.language();

    Patient p = new Patient();
    String text = family + " " + String.join(" ", given);
    var name = p.addName().setUse(NameUse.OFFICIAL).setText(text).setFamily(family);
    given.forEach(name::addGiven);
    p.addTelecom().setSystem(ContactPointSystem.PHONE).setValue(phone);
    if (r.email() != null && !r.email().isBlank()) {
      p.addTelecom().setSystem(ContactPointSystem.EMAIL).setValue(r.email().strip());
    }
    p.setGender(AdministrativeGender.fromCode(r.gender()));
    p.setBirthDateElement(new DateType(birth.toString()));
    if (r.address() != null) {
      var address = p.addAddress();
      if (r.address().city() != null) {
        address.setCity(r.address().city());
      }
      if (r.address().district() != null) {
        address.setDistrict(r.address().district());
      }
    }
    p.addCommunication()
        .setLanguage(
            new CodeableConcept()
                .addCoding(new Coding().setSystem(LANGUAGE_SYSTEM).setCode(language)));
    return p;
  }

  private static ShpException invalid(String detail) {
    return new ShpException("validation-failed", detail);
  }
}
