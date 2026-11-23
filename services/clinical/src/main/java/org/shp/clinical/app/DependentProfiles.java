package org.shp.clinical.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.shp.clinical.domain.PatientNormalizer;
import org.shp.clinical.infra.MongoProfileLinks;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.events.Uuids;
import org.shp.web.errors.ErrorCatalog;
import org.shp.web.errors.ErrorInfo;
import org.shp.web.errors.ShpException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Profiles an account manages (09 §9 C22, C23; ADR-0018; US-076): the list, and creating a record
 * for a person under 16 linked to the guardian ({@code relationship=guardian}, {@code scope=full}).
 * The Patient and its {@code profile_links} entry are written in one transaction.
 */
@Service
public class DependentProfiles {
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  public static final int MAX_PROFILES = 10;
  static final int DEPENDENT_AGE = 16;
  static final Set<String> GENDERS = Set.of("male", "female", "other", "unknown");
  static final Set<String> COUNTED = Set.of("pending", "active");

  /** 09 §9 codes of the shared catalog (07 §5). */
  public static final ErrorInfo NOT_A_DEPENDENT = ErrorCatalog.get("not-a-dependent");

  public static final ErrorInfo PROFILE_LIMIT = ErrorCatalog.get("profile-limit");

  /** A 09 §9 business rule refusal with its local catalog entry. */
  public static class RuleException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient ErrorInfo info;

    RuleException(ErrorInfo info, String detail) {
      super(detail);
      this.info = info;
    }

    public ErrorInfo info() {
      return info;
    }
  }

  /** One row of C22. */
  public record Profile(
      String profileId,
      String patientId,
      String displayName,
      String relationship,
      String scope,
      Instant expiresAt) {}

  /** C23 body. */
  public record DependentRequest(
      String family, List<String> given, String birthDate, String gender, Boolean declaration) {}

  private final PatientRepository patients;
  private final MongoTemplate mongo;
  private final Clock clock;

  public DependentProfiles(PatientRepository patients, MongoTemplate mongo, Clock clock) {
    this.patients = patients;
    this.mongo = mongo;
    this.clock = clock;
  }

  /**
   * C22: the account's own record ({@code self}) then the linked ones that are pending or active
   * and not expired; a link that is not active yet shows a shortened name.
   */
  public List<Profile> list(String accountSub, String ownPatientId) {
    List<Profile> out = new ArrayList<>();
    if (ownPatientId != null) {
      patients
          .find(ownPatientId)
          .ifPresent(
              e -> out.add(new Profile("self", ownPatientId, name(e), "self", "full", null)));
    }
    for (Document link : links(accountSub)) {
      String patientId = link.getString("patientId");
      boolean active = "active".equals(link.getString("status"));
      Optional<Entry> e = patients.find(patientId);
      String display =
          e.map(DependentProfiles::name).map(n -> active ? n : shorten(n)).orElse(null);
      Date exp = link.getDate("expiresAt");
      out.add(
          new Profile(
              link.getString("_id"),
              patientId,
              display,
              link.getString("relationship"),
              link.getString("scope"),
              exp == null ? null : exp.toInstant()));
    }
    return out;
  }

  /** C23: 201 with the new profile; 400, 422 not-a-dependent / profile-limit / duplicate. */
  public Profile createDependent(
      String accountSub, String ownPatientId, DependentRequest r, String changedBy) {
    if (r == null || !Boolean.TRUE.equals(r.declaration())) {
      throw new ShpException("validation-failed", "declaration must be true.");
    }
    String family = r.family() == null ? "" : r.family().strip();
    List<String> given =
        r.given() == null
            ? List.of()
            : r.given().stream().filter(g -> g != null && !g.isBlank()).map(String::strip).toList();
    if (family.isEmpty() || given.isEmpty()) {
      throw new ShpException("validation-failed", "name.family and name.given are required.");
    }
    if (r.gender() == null || !GENDERS.contains(r.gender())) {
      throw new ShpException("validation-failed", "gender must be male, female, other or unknown.");
    }
    LocalDate birth;
    try {
      birth = LocalDate.parse(r.birthDate());
    } catch (RuntimeException e) {
      throw new ShpException("validation-failed", "birthDate must be a date (yyyy-MM-dd).");
    }
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    if (birth.isAfter(today) || Period.between(birth, today).getYears() >= DEPENDENT_AGE) {
      throw new RuleException(NOT_A_DEPENDENT, "The person must be under 16.");
    }
    if (ownPatientId == null) {
      throw new ShpException("validation-failed", "Register your own record first.");
    }
    if (links(accountSub).size() >= MAX_PROFILES) {
      throw new RuleException(PROFILE_LIMIT, "At most 10 profiles per account.");
    }
    String text = family + " " + String.join(" ", given);
    if (duplicate(text, birth.toString())) {
      throw new ShpException("duplicate-patient-suspected");
    }
    Entry guardian = patients.find(ownPatientId).orElseThrow(() -> new ShpException("not-found"));
    String guardianPhone =
        PatientRepository.toFhir(guardian).getTelecom().stream()
            .filter(t -> t.getSystem() == ContactPointSystem.PHONE)
            .map(ContactPoint::getValue)
            .findFirst()
            .orElseThrow(() -> new ShpException("validation-failed", "Your record has no phone."));

    Patient p = new Patient();
    var name = p.addName().setUse(NameUse.OFFICIAL).setText(text).setFamily(family);
    given.forEach(name::addGiven);
    p.addTelecom()
        .setSystem(ContactPointSystem.PHONE)
        .setValue(guardianPhone); // reached through the guardian
    p.setGender(AdministrativeGender.fromCode(r.gender()));
    p.setBirthDateElement(new DateType(birth.toString()));
    p.addCommunication()
        .setLanguage(new CodeableConcept().addCoding(new Coding("urn:ietf:bcp:47", "vi", null)));

    String linkId = Uuids.v7(clock).toString();
    Instant now = clock.instant();
    Entry created =
        patients.create(
            p,
            changedBy,
            null,
            entry ->
                mongo.insert(
                    new Document("_id", linkId)
                        .append("accountSub", accountSub)
                        .append("patientId", entry.id())
                        .append("relationship", "guardian")
                        .append("scope", "full")
                        .append("status", "active")
                        .append("establishedBy", "guardian-declaration")
                        .append("expiresAt", null)
                        .append("createdAt", Date.from(now)),
                    MongoProfileLinks.COLLECTION));
    return new Profile(linkId, created.id(), text, "guardian", "full", null);
  }

  /** The account's links that count against the limit: pending or active, not expired. */
  private List<Document> links(String accountSub) {
    Criteria notExpired =
        new Criteria()
            .orOperator(
                Criteria.where("expiresAt").is(null),
                Criteria.where("expiresAt").gt(Date.from(clock.instant())));
    Query q =
        Query.query(
                Criteria.where("accountSub")
                    .is(accountSub)
                    .and("status")
                    .in(COUNTED)
                    .andOperator(notExpired))
            .with(Sort.by("createdAt", "_id"));
    return mongo.find(q, Document.class, MongoProfileLinks.COLLECTION);
  }

  /** An active record with the same normalized name and birth date already exists. */
  private boolean duplicate(String name, String birthDate) {
    return mongo.exists(
        Query.query(
            Criteria.where("search.nameTokens")
                .all(PatientNormalizer.nameTokens(name))
                .and("search.birthDate")
                .is(birthDate)
                .and("search.active")
                .is(true)),
        "patients");
  }

  private static String name(Entry e) {
    return PatientRepository.toFhir(e).getNameFirstRep().getText();
  }

  /** "Nguyen Van An" → "N. V. An": shown until the link is active (09 §9 C22). */
  static String shorten(String name) {
    if (name == null || name.isBlank()) {
      return name;
    }
    String[] parts = name.strip().split("\\s+");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length - 1; i++) {
      sb.append(parts[i].charAt(0)).append(". ");
    }
    return sb.append(parts[parts.length - 1]).toString();
  }
}
