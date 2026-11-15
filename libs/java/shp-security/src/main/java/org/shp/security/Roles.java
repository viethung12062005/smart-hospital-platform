package org.shp.security;

import java.util.Set;

/** Valid roles (match contracts/authz/matrix.yaml). */
public final class Roles {
  public static final Set<String> ALL = Set.of("patient", "doctor", "nurse", "admin", "dpo");

  /** Staff role: MFA required (NFR-SEC1). */
  public static final Set<String> STAFF = Set.of("doctor", "nurse", "admin", "dpo");

  private Roles() {}
}
