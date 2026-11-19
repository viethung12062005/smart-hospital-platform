package org.shp.clinical.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.shp.clinical.app.ClinicalReads.RoleView;
import org.springframework.stereotype.Service;

/**
 * The answers of the internal API (09 §4) other services call: care check, practitioner roles,
 * patient contact and age band. The caller is checked by {@code InternalLookupFilter}.
 */
@Service
public class InternalLookups {
  /** Ages are counted in the hospital's time zone (01 §4.1). */
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  static final String UNKNOWN_AGE_BAND = "unknown";
  static final String DEFAULT_LOCALE = "vi";

  /** {@code care: false} carries neither {@code via} nor {@code validUntil}. */
  public record CareCheck(boolean care, String via, Instant validUntil) {
    static final CareCheck NONE = new CareCheck(false, null, null);
  }

  /** The only answer that holds contact data (09 §4); every call is audited by the caller. */
  public record Contact(String phone, String email, String locale) {}

  private final ClinicalReads reads;
  private final Clock clock;

  public InternalLookups(ClinicalReads reads, Clock clock) {
    this.reads = reads;
    this.clock = clock;
  }

  /**
   * 15 §4: a valid care relationship wins; otherwise an unexpired break-glass session (15 §5);
   * otherwise no care.
   */
  public CareCheck care(String practitionerId, String patientId) {
    Instant now = Instant.now(clock);
    var row = reads.careRelationship(practitionerId, patientId, now);
    if (row.isPresent()) {
      return new CareCheck(true, row.get().via(), row.get().validUntil());
    }
    return reads
        .breakGlass(practitionerId, patientId, now)
        .map(until -> new CareCheck(true, "break-glass", until))
        .orElse(CareCheck.NONE);
  }

  /** Empty when the practitioner does not exist (404); otherwise its roles, possibly none. */
  public Optional<List<RoleView>> roles(String practitionerId) {
    if (!reads.practitionerExists(practitionerId)) {
      return Optional.empty();
    }
    return Optional.of(reads.roles(practitionerId));
  }

  /** Empty when the patient does not exist (404). */
  public Optional<Contact> contact(String patientId) {
    return reads
        .patient(patientId)
        .map(p -> new Contact(p.phone(), p.email(), locale(p.language())));
  }

  /** Empty when the patient does not exist (404); {@code unknown} without a birth date. */
  public Optional<String> ageBand(String patientId) {
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    return reads.patient(patientId).map(p -> ageBand(p.birthDate(), today));
  }

  /** Ten-year band {@code 30-39} (04-db-notification-ml §2.1: {@code ^\d{1,3}-\d{1,3}$}). */
  static String ageBand(LocalDate birthDate, LocalDate today) {
    if (birthDate == null || birthDate.isAfter(today)) {
      return UNKNOWN_AGE_BAND;
    }
    int low = Period.between(birthDate, today).getYears() / 10 * 10;
    return low + "-" + (low + 9);
  }

  /** {@code communication.language} is {@code vi} or {@code en} (01 §4.1); default {@code vi}. */
  static String locale(String language) {
    return "en".equals(language) ? "en" : DEFAULT_LOCALE;
  }
}
