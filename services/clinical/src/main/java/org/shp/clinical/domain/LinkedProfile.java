package org.shp.clinical.domain;

import java.time.Instant;

/**
 * A profile an account manages through {@code profile_links} (03 §2.2, ADR-0018).
 *
 * @param patientId id of the managed Patient (without the type)
 * @param relationship {@code guardian} or {@code proxy}
 * @param scope {@code booking}, {@code records} or {@code full}
 * @param expiresAt end of the link; null when it does not expire
 */
public record LinkedProfile(
    String patientId, String relationship, String scope, Instant expiresAt) {}
