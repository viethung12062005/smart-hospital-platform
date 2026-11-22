package org.shp.clinical.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Read-only views of the clinical collections that the internal API (09 §4) answers from. The
 * documents are written by other parts of clinical (patients, practitioner roles, care
 * relationships, break-glass sessions); this port only reads them.
 */
public interface ClinicalReads {

  /** The fields of a stored Patient that the internal API may use. Never logged. */
  record PatientFacts(LocalDate birthDate, String phone, String email, String language) {}

  /** A stored PractitionerRole, reduced to what scheduling checks. */
  record RoleView(String id, String specialty, String code, boolean active) {}

  /** A non-expired care relationship: its validity and the type of its most recent source. */
  record CareRow(Instant validUntil, String via) {}

  /** A non-deleted Patient by id. */
  Optional<PatientFacts> patient(String patientId);

  /** Whether a non-deleted Practitioner exists. */
  boolean practitionerExists(String practitionerId);

  /** Every non-deleted PractitionerRole of the practitioner, active or not. */
  List<RoleView> roles(String practitionerId);

  /** The care relationship of the pair when {@code validUntil > now} (15 §4). */
  Optional<CareRow> careRelationship(String practitionerId, String patientId, Instant now);

  /** The expiry of the latest unexpired break-glass session of the pair (15 §5). */
  Optional<Instant> breakGlass(String practitionerId, String patientId, Instant now);
}
