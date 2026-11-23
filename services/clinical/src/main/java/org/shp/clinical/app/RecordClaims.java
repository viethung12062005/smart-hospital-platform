package org.shp.clinical.app;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bson.Document;
import org.shp.clinical.domain.PatientNormalizer;
import org.shp.clinical.infra.IdentityLinkRepository;
import org.shp.clinical.infra.LinkAttempts;
import org.shp.events.audit.AuditActions;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.AuditRecordBuilder;
import org.shp.events.audit.AuditSink;
import org.shp.web.errors.ErrorCatalog;
import org.shp.web.errors.ErrorInfo;
import org.shp.web.errors.ShpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Self-claim of an existing record (09 §9 C27, C28; ADR-0018 §2; US-078). C27 answers the same way
 * whether or not a record matches: a code is sent only when exactly one active record matches the
 * name, birth date and phone number, to the number stored in that record. C28 links the account
 * when the code is right and no other account holds the record as "self". The phone number and
 * birth date entered never reach logs or audit.
 */
@Service
public class RecordClaims {
  static final Logger LOG = LoggerFactory.getLogger(RecordClaims.class);
  static final String SERVICE = "clinical";
  static final String KIND = "claim";
  static final String PURPOSE = "claim-record";
  static final Duration WINDOW = Duration.ofHours(24);
  static final Duration CODE_VALID = Duration.ofMinutes(5);
  public static final int MAX_CLAIMS = 5;
  public static final int MAX_CODES_PER_NUMBER = 3;
  public static final int MAX_WRONG_CODES = 5;
  static final Pattern CODE = Pattern.compile("\\d{6}");

  /** 09 §9 codes not yet in the shared catalog (07 §5). */
  public static final ErrorInfo OTP_RATE_LIMITED = ErrorCatalog.get("otp-rate-limited");

  public static final ErrorInfo OTP_INVALID = ErrorCatalog.get("otp-invalid");

  public static final ErrorInfo OTP_LOCKED = ErrorCatalog.get("otp-locked");

  public static final ErrorInfo RECORD_ALREADY_LINKED = ErrorCatalog.get("record-already-linked");

  /** A 09 §9 refusal with its local catalog entry. */
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

  /** C27 body. */
  public record ClaimRequest(String family, List<String> given, String birthDate, String phone) {}

  private final MongoTemplate mongo;
  private final LinkAttempts attempts;
  private final OtpClient otp;
  private final IdentityLinkRepository links;
  private final AuditSink audit;
  private final Clock clock;

  public RecordClaims(
      MongoTemplate mongo,
      LinkAttempts attempts,
      OtpClient otp,
      IdentityLinkRepository links,
      AuditSink audit,
      Clock clock) {
    this.mongo = mongo;
    this.attempts = attempts;
    this.otp = otp;
    this.links = links;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * C27. Returns normally whether or not a record matched; only a malformed request (400), an
   * account that already has a record (409) or the limit per account (429) answer differently, and
   * none of them depends on the records.
   */
  public void claim(String subject, boolean alreadyLinked, ClaimRequest r) {
    if (alreadyLinked) {
      throw new ShpException("already-registered");
    }
    if (r == null) {
      throw new ShpException("validation-failed", "name, birthDate and phone are required.");
    }
    String family = r.family() == null ? "" : r.family().strip();
    List<String> given =
        r.given() == null
            ? List.of()
            : r.given().stream().filter(g -> g != null && !g.isBlank()).map(String::strip).toList();
    if (family.isEmpty() || given.isEmpty()) {
      throw new ShpException("validation-failed", "name.family and name.given are required.");
    }
    String birthDate;
    try {
      birthDate = LocalDate.parse(r.birthDate()).toString();
    } catch (RuntimeException e) {
      throw new ShpException("validation-failed", "birthDate must be a date (yyyy-MM-dd).");
    }
    String phone =
        PatientNormalizer.normalizePhone(r.phone())
            .orElseThrow(
                () -> new ShpException("validation-failed", "phone is not a valid mobile number."));

    if (attempts.byAccount(subject, KIND, WINDOW) >= MAX_CLAIMS) {
      audit.emit(record(subject, AuditActions.DENIED, null, "claim-record: limit per account"));
      throw new RuleException(OTP_RATE_LIMITED, "At most 5 claims per account in 24 hours.");
    }

    List<String> tokens = PatientNormalizer.nameTokens(family + " " + String.join(" ", given));
    List<Document> candidates =
        mongo.find(
            Query.query(
                    Criteria.where("search.nameTokens")
                        .all(tokens)
                        .size(tokens.size())
                        .and("search.birthDate")
                        .is(birthDate)
                        .and("search.phone")
                        .is(phone)
                        .and("search.active")
                        .is(true))
                .limit(2),
            Document.class,
            "patients");
    String phoneHash = attempts.phoneHash(phone);
    String patientId = null;
    String challengeId = null;
    // exactly one candidate, and the number in it (the one matched) has codes left
    if (candidates.size() == 1 && attempts.codesSentTo(phoneHash, WINDOW) < MAX_CODES_PER_NUMBER) {
      try {
        challengeId = otp.send(PURPOSE, phone, "vi");
        patientId = candidates.getFirst().getString("_id");
      } catch (RuntimeException e) {
        // the answer stays the same; the person can ask again or go to the counter
        LOG.warn("No code sent for a record claim: {}", e.getClass().getSimpleName());
      }
    }
    attempts.record(subject, KIND, phoneHash, patientId, challengeId);
  }

  /** C28: link the account to the claimed record; returns its id. */
  public String verify(String subject, String issuer, boolean alreadyLinked, String code) {
    if (alreadyLinked) {
      throw new ShpException("already-registered");
    }
    if (code == null || !CODE.matcher(code).matches()) {
      throw new ShpException("validation-failed", "otp must be 6 digits.");
    }
    Document claim =
        attempts
            .latestOpen(subject, KIND, CODE_VALID)
            .orElseThrow(() -> new RuleException(OTP_INVALID, "No code is waiting."));
    String id = claim.getString("_id");
    if (claim.getInteger("failures", 0) >= MAX_WRONG_CODES) {
      throw new RuleException(OTP_LOCKED, "Too many wrong codes.");
    }
    String challengeId = claim.getString("challengeId");
    OtpClient.Result result =
        challengeId == null ? OtpClient.Result.INVALID : otp.verify(challengeId, code);
    if (result == OtpClient.Result.LOCKED) {
      throw new RuleException(OTP_LOCKED, "Too many wrong codes.");
    }
    if (result == OtpClient.Result.INVALID) {
      if (attempts.fail(id) >= MAX_WRONG_CODES) {
        audit.emit(record(subject, AuditActions.DENIED, null, "claim-record: code locked"));
      }
      throw new RuleException(OTP_INVALID, "The code is wrong or has expired.");
    }
    if (!attempts.use(id)) {
      throw new RuleException(OTP_INVALID, "The code was used already.");
    }
    String patientId = claim.getString("patientId");
    if (mongo.exists(
        Query.query(Criteria.where("fhirUser").is("Patient/" + patientId)),
        IdentityLinkRepository.COLLECTION)) {
      audit.emit(
          record(subject, AuditActions.DENIED, patientId, "claim-record: linked to another"));
      throw new RuleException(RECORD_ALREADY_LINKED, "Please go to the counter.");
    }
    try {
      links.linkPatient(subject, issuer, patientId, subject);
    } catch (DuplicateKeyException e) {
      audit.emit(
          record(subject, AuditActions.DENIED, patientId, "claim-record: linked to another"));
      throw new RuleException(RECORD_ALREADY_LINKED, "Please go to the counter.");
    }
    audit.emit(record(subject, AuditActions.SUCCESS, patientId, "claim-record: code verified"));
    return patientId;
  }

  private AuditRecord record(String subject, String outcome, String patientId, String reason) {
    UUID uuid = null;
    if (patientId != null) {
      try {
        uuid = UUID.fromString(patientId);
      } catch (IllegalArgumentException e) {
        uuid = null;
      }
    }
    return AuditRecordBuilder.build(
        clock,
        SERVICE,
        AuditActions.CREATE,
        outcome,
        new AuditRecord.Actor(subject, "patient", null),
        patientId == null ? "Patient" : "Patient/" + patientId,
        uuid,
        null,
        reason,
        null,
        null,
        null);
  }
}
