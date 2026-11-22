package org.shp.clinical.app;

import java.time.LocalDate;
import org.bson.Document;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.shp.clinical.infra.NationalIdVault;
import org.shp.clinical.infra.PatientRepository;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Component;

/**
 * Writing the CCCD through C5 (09 §2; US-075; AC-ID.1–AC-ID.3): a nurse, with the patient's consent
 * extension, a 12-digit number whose gender/century digit and birth-year digits match the record
 * (or a reason when they do not), not held by another record. The result is the encrypted search
 * fields; the clear number never reaches the stored resource.
 */
@Component
public class NationalIdRules {
  public static final String SYSTEM = "urn:shp:cccd";
  public static final String CONSENT =
      "https://shp.example.org/fhir/StructureDefinition/national-id-consent";
  public static final String REASON =
      "https://shp.example.org/fhir/StructureDefinition/national-id-reason";

  private final NationalIdVault vault;
  private final PatientRepository patients;

  public NationalIdRules(NationalIdVault vault, PatientRepository patients) {
    this.vault = vault;
    this.patients = patients;
  }

  /** A masked value sent back unchanged ({@code ********1234}): not a new number. */
  public static boolean isMasked(Identifier id) {
    return id.getValue() != null && id.getValue().startsWith("*");
  }

  /**
   * @param nurse whether the caller is a nurse (only nurses write the number)
   * @return the {@code search} fields to store
   */
  public Document accept(boolean nurse, Identifier given, Patient record, String patientId) {
    if (!nurse) {
      throw new ShpException("forbidden", "Only a nurse records the national ID number.");
    }
    String number = given.getValue();
    if (number == null || !number.matches("^\\d{12}$")) {
      throw new ShpException("validation-failed", "Patient.identifier[cccd] must be 12 digits.");
    }
    Extension consent = given.getExtensionByUrl(CONSENT);
    if (consent == null
        || !(consent.getValue() instanceof BooleanType b)
        || !Boolean.TRUE.equals(b.getValue())) {
      throw new ShpException(
          "validation-failed", "Patient.identifier[cccd] needs the patient's consent extension.");
    }
    if (!matchesRecord(number, record)) {
      Extension reason = given.getExtensionByUrl(REASON);
      String text =
          reason != null && reason.getValue() instanceof StringType s ? s.getValue() : null;
      if (text == null || text.strip().length() < 10 || text.strip().length() > 200) {
        throw new ShpException(
            "validation-failed",
            "Patient.identifier[cccd] does not match gender or birth year: a reason is required.");
      }
    }
    String hash = vault.hash(number);
    if (patients.nationalIdHeldByAnother(hash, patientId)) {
      throw new ShpException("duplicate-patient-suspected"); // AC-ID.2: suggest merging
    }
    return new Document("nationalIdEnc", vault.encrypt(number))
        .append("nationalIdLast4", number.substring(8))
        .append("nationalIdHash", hash);
  }

  /**
   * CCCD digit 4 codes century and gender (0/1: 1900s male/female, 2/3: 2000s, 4/5: 2100s...),
   * digits 5–6 the last two digits of the birth year.
   */
  static boolean matchesRecord(String number, Patient p) {
    if (!p.hasBirthDate()) {
      return false;
    }
    int code = number.charAt(3) - '0';
    int year = 1900 + (code / 2) * 100 + Integer.parseInt(number.substring(4, 6));
    AdministrativeGender gender =
        code % 2 == 0 ? AdministrativeGender.MALE : AdministrativeGender.FEMALE;
    int birthYear = LocalDate.parse(p.getBirthDateElement().getValueAsString()).getYear();
    return year == birthYear && p.getGender() == gender;
  }
}
