package org.shp.clinical.app;

import java.time.Instant;
import java.util.List;
import org.shp.clinical.domain.LinkedProfile;

/** Read port of {@code profile_links}. */
public interface ProfileLinks {
  /**
   * Links of the account that are {@code active} and not expired at {@code now}; pending, revoked
   * and expired ones are ignored. Ordered by creation time, then patient id.
   */
  List<LinkedProfile> activeFor(String accountSub, Instant now);
}
