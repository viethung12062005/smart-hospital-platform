package org.shp.web.auth;

import java.util.Set;

/** Các role hợp lệ (khớp contracts/authz/matrix.yaml). */
public final class Roles {
  public static final Set<String> ALL = Set.of("patient", "doctor", "nurse", "admin", "dpo");

  /** Role nhân viên: bắt buộc MFA (NFR-SEC1). */
  public static final Set<String> STAFF = Set.of("doctor", "nurse", "admin", "dpo");

  private Roles() {}
}
