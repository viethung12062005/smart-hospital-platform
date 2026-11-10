package org.shp.authz;

import java.time.Instant;

/**
 * Facts about the resource being accessed. The service looks them up first (care relationship,
 * break-glass...) then passes them in; the engine is a pure function. A missing fact (null) is
 * always treated as a predicate that does NOT hold (deny when unsure).
 *
 * @param encounterFinished true = encounter already finished, false = not yet, null = irrelevant or
 *     unknown
 * @param linkedProfile the record is in the caller's linkedProfiles (active link, scope from
 *     booking); ADR-0018
 * @param linkedRecords like linkedProfile but scope records or full (visit results, documents)
 * @param guardianLink the link has a guardian relationship
 */
public record ResourceContext(
    String patientId,
    String practitionerId,
    boolean hasCareRelationship,
    boolean hasActiveBreakGlass,
    Boolean releasedToPatient,
    Boolean encounterFinished,
    Instant appointmentStart,
    boolean linkedProfile,
    boolean linkedRecords,
    boolean guardianLink) {

  public static ResourceContext empty() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder so a service only sets the facts it has. */
  public static final class Builder {
    private String patientId;
    private String practitionerId;
    private boolean hasCareRelationship;
    private boolean hasActiveBreakGlass;
    private Boolean releasedToPatient;
    private Boolean encounterFinished;
    private Instant appointmentStart;
    private boolean linkedProfile;
    private boolean linkedRecords;
    private boolean guardianLink;

    private Builder() {}

    public Builder patientId(String v) {
      patientId = v;
      return this;
    }

    public Builder practitionerId(String v) {
      practitionerId = v;
      return this;
    }

    public Builder hasCareRelationship(boolean v) {
      hasCareRelationship = v;
      return this;
    }

    public Builder hasActiveBreakGlass(boolean v) {
      hasActiveBreakGlass = v;
      return this;
    }

    public Builder releasedToPatient(Boolean v) {
      releasedToPatient = v;
      return this;
    }

    public Builder encounterFinished(Boolean v) {
      encounterFinished = v;
      return this;
    }

    public Builder appointmentStart(Instant v) {
      appointmentStart = v;
      return this;
    }

    public Builder linkedProfile(boolean v) {
      linkedProfile = v;
      return this;
    }

    public Builder linkedRecords(boolean v) {
      linkedRecords = v;
      return this;
    }

    public Builder guardianLink(boolean v) {
      guardianLink = v;
      return this;
    }

    public ResourceContext build() {
      return new ResourceContext(
          patientId,
          practitionerId,
          hasCareRelationship,
          hasActiveBreakGlass,
          releasedToPatient,
          encounterFinished,
          appointmentStart,
          linkedProfile,
          linkedRecords,
          guardianLink);
    }
  }
}
