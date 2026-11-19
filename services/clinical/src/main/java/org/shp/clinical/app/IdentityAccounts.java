package org.shp.clinical.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.shp.clinical.domain.IdentityAccount;

/** Persistence port of {@code identity_links}. */
public interface IdentityAccounts {
  Optional<IdentityAccount> find(String subject);

  /** Accounts ordered by subject, strictly after {@code afterSubject} (null = from the start). */
  List<IdentityAccount> page(String afterSubject, int limit);

  /**
   * Inserts a link.
   *
   * @return false when the subject or the {@code fhirUser} is already linked
   */
  boolean insert(IdentityAccount account, String issuer, String createdBy);

  /**
   * Sets the lock flag if the stored version is {@code expectedVersion} (compare-and-set).
   *
   * @return the new state; empty when the subject has no link or the version no longer matches
   */
  Optional<IdentityAccount> setDisabled(
      String subject, boolean disabled, long expectedVersion, Instant at, String updatedBy);
}
