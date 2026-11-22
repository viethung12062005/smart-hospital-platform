package org.shp.authz;

import java.time.Instant;

/**
 * A profile the caller manages through {@code profile_links} (ADR-0018), as returned by the
 * internal identity lookup.
 *
 * @param patientId id of the managed Patient (without the type)
 * @param relationship {@code guardian} or {@code proxy}
 * @param scope {@code booking}, {@code records} or {@code full}
 * @param expiresAt end of the link; null when it does not expire
 */
public record LinkedProfile(
    String patientId, String relationship, String scope, Instant expiresAt) {

  public static final String GUARDIAN = "guardian";

  /** A link past its {@code expiresAt} is never honoured, even if clinical has not marked it. */
  public boolean activeAt(Instant now) {
    return expiresAt == null || expiresAt.isAfter(now);
  }

  /** Scope {@code records} or {@code full}: visit results and released documents (15 §3). */
  public boolean coversRecords() {
    return "records".equals(scope) || "full".equals(scope);
  }

  public boolean isGuardian() {
    return GUARDIAN.equals(relationship);
  }
}
