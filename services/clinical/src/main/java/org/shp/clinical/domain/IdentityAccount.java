package org.shp.clinical.domain;

import java.time.Instant;
import java.util.List;

/**
 * A row of {@code identity_links} (03 §2.2): the link between an OIDC subject and a Patient or
 * Practitioner, the account's roles and its lock state.
 *
 * @param fhirUser {@code Patient/{id}} or {@code Practitioner/{id}}
 * @param roles login roles granted to the account ({@code doctor}, {@code nurse}, ...)
 * @param disabled true when an Admin locked the account (C21)
 * @param version optimistic-lock version, 1 on creation; rows written before versioning read as 1
 */
public record IdentityAccount(
    String subject,
    String fhirUser,
    List<String> roles,
    boolean disabled,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
