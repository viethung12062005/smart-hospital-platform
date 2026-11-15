package org.shp.clinical.app;

import org.shp.clinical.infra.TerminologyService;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Component;

/**
 * Rejects an Observation {@code code} that is not in the LOINC vital-signs value set (01 §4.7).
 * CLN-16 calls this before persisting an Observation; a fuller validator (UCUM unit, plausible
 * range, interpretation) builds on it.
 */
@Component
public class ObservationCodeValidator {
  private static final String SYSTEM = "loinc-vital-signs";

  private final TerminologyService terminology;

  public ObservationCodeValidator(TerminologyService terminology) {
    this.terminology = terminology;
  }

  public void validate(String code) {
    if (!terminology.isKnown(SYSTEM, code)) {
      throw new ShpException("validation-failed", "Unknown Observation code.");
    }
  }
}
