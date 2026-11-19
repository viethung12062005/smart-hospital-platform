package org.shp.clinical.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.shp.events.Uuids;
import org.shp.web.errors.ShpException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Break-glass (09 C20; 15 §5; US-034): a doctor without care opens a 60-minute, read-only session
 * on one patient with a 20–500 character reason. The session lives in {@code break_glass_sessions}
 * (03 §2.2), which a TTL index on {@code expiresAt} empties automatically; the {@code
 * *-READ-DOC-BG} rules read it through {@link ClinicalReads#breakGlass}. Writing still needs care.
 */
@Service
public class BreakGlassService {
  public static final String COLLECTION = "break_glass_sessions";
  public static final Duration TTL = Duration.ofMinutes(60);
  static final int MIN_REASON = 20;
  static final int MAX_REASON = 500;

  private final MongoTemplate mongo;
  private final ClinicalReads reads;
  private final Clock clock;

  public BreakGlassService(MongoTemplate mongo, ClinicalReads reads, Clock clock) {
    this.mongo = mongo;
    this.reads = reads;
    this.clock = clock;
  }

  /**
   * Starts a session. 400 for a reason outside 20–500 characters; 404 for an unknown patient; 409
   * {@code invalid-state-transition} when the doctor already has care or an unexpired session.
   *
   * @param practitionerId the calling doctor ({@code null} for any other caller → 403)
   * @return the session's expiry
   */
  public Instant start(String practitionerId, String patientId, String reason) {
    if (practitionerId == null) {
      throw new ShpException("forbidden", "Only a doctor can start break-glass.");
    }
    String trimmed = reason == null ? "" : reason.strip();
    if (trimmed.length() < MIN_REASON || trimmed.length() > MAX_REASON) {
      throw new ShpException("validation-failed", "reason must be 20 to 500 characters.");
    }
    if (patientId == null || !patientId.matches("^[A-Za-z0-9-]{1,64}$")) {
      throw new ShpException("validation-failed", "patientId must be a Patient id.");
    }
    if (reads.patient(patientId).isEmpty()) {
      throw new ShpException("not-found");
    }
    Instant now = clock.instant();
    if (reads.careRelationship(practitionerId, patientId, now).isPresent()) {
      throw new ShpException(
          "invalid-state-transition", "A care relationship exists; break-glass is not needed.");
    }
    if (reads.breakGlass(practitionerId, patientId, now).isPresent()) {
      throw new ShpException("invalid-state-transition", "A break-glass session is still valid.");
    }
    Instant expiresAt = now.plus(TTL);
    mongo.insert(
        new Document("_id", Uuids.v7(clock).toString())
            .append("practitionerId", practitionerId)
            .append("patientId", patientId)
            .append("reason", trimmed)
            .append("startedAt", Date.from(now))
            .append("expiresAt", Date.from(expiresAt)),
        COLLECTION);
    return expiresAt;
  }
}
