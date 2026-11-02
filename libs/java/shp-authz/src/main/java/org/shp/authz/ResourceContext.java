package org.shp.authz;

import java.time.Instant;

/**
 * Dữ kiện về tài nguyên đang được truy cập. Service tra cứu trước (quan hệ điều trị,
 * break-glass...) rồi truyền vào; engine là hàm thuần. Dữ kiện thiếu (null) luôn được coi là
 * predicate KHÔNG thỏa (từ chối khi không chắc).
 *
 * @param encounterFinished true = encounter đã finished, false = chưa, null = không liên quan/không
 *     biết
 * @param linkedProfile hồ sơ nằm trong linkedProfiles của người gọi (liên kết active, phạm vi từ
 *     booking); ADR-0018
 * @param linkedRecords như linkedProfile nhưng phạm vi records hoặc full (kết quả khám, tài liệu)
 * @param guardianLink liên kết có quan hệ người giám hộ
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

  /** Builder để service chỉ đặt dữ kiện mình có. */
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
