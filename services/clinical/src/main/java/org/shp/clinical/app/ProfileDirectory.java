package org.shp.clinical.app;

import java.util.Set;

/** Read-only view of the staff and patient records an account can be linked to. */
public interface ProfileDirectory {
  boolean patientExists(String patientId);

  boolean practitionerExists(String practitionerId);

  /** {@code PractitionerRole.code} values ({@code doctor}, {@code nurse}) that are active. */
  Set<String> activeRoleCodes(String practitionerId);
}
