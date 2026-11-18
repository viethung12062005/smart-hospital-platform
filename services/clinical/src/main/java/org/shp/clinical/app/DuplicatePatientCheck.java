package org.shp.clinical.app;

import java.util.Optional;
import org.shp.clinical.domain.PatientNormalizer;
import org.shp.clinical.infra.PatientRepository;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Component;

/**
 * Duplicate record detection at registration (AC-01.2, 03 §2.6): an active record with the same
 * phone number and date of birth makes a new record suspect. The refusal is {@code
 * duplicate-patient-suspected} and carries nothing from the existing record: no id, MRN, name or
 * phone, not even in the log or the message, so the check cannot be used to look people up.
 */
@Component
public class DuplicatePatientCheck {
  private final PatientRepository patients;

  public DuplicatePatientCheck(PatientRepository patients) {
    this.patients = patients;
  }

  /**
   * True when an active patient has this phone number (any common Vietnamese format) and birth date
   * ({@code yyyy-MM-dd}). An unparseable phone number cannot match a stored E.164 number.
   */
  public boolean isSuspected(String phone, String birthDate) {
    Optional<String> e164 = PatientNormalizer.normalizePhone(phone);
    return e164.isPresent() && patients.existsActiveByPhoneAndBirthDate(e164.get(), birthDate);
  }

  /** Throws {@code duplicate-patient-suspected} (HTTP 422) when {@link #isSuspected} holds. */
  public void requireNoDuplicate(String phone, String birthDate) {
    if (isSuspected(phone, birthDate)) {
      throw new ShpException("duplicate-patient-suspected");
    }
  }
}
