package org.shp.scheduling.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Filter criteria and keyset pagination cursor for S13 {@code GET /fhir/Appointment} (08 §4). At
 * least one of {@code patientId}, {@code practitionerId}, {@code startFrom}, or {@code startTo}
 * must be present.
 */
public record AppointmentSearchCriteria(
    UUID patientId,
    UUID practitionerId,
    Set<String> statuses,
    String specialty,
    Instant startFrom,
    Instant startTo,
    boolean descending,
    Instant afterStartAt,
    UUID afterId,
    int limit) {

  /** Allowed FHIR Appointment statuses in the scheduling service (01 §4.5). */
  public static final Set<String> ALLOWED_STATUSES =
      Set.of("booked", "arrived", "fulfilled", "cancelled", "noshow");

  /** Canonical CodeSystem URL for medical specialties (01 §3). */
  public static final String SPECIALTY_SYSTEM = "https://shp.example.org/fhir/CodeSystem/specialty";

  /** Valid specialty codes (01 §3, contracts/terminology/code-systems.yaml). */
  public static final Set<String> ALLOWED_SPECIALTIES =
      Set.of(
          "internal-medicine",
          "cardiology",
          "pediatrics",
          "obstetrics-gynecology",
          "dermatology",
          "ent",
          "ophthalmology",
          "orthopedics",
          "neurology",
          "gastroenterology",
          "endocrinology",
          "general-surgery");

  public AppointmentSearchCriteria {
    if (statuses == null || statuses.isEmpty()) {
      statuses = Set.of();
    } else {
      if (!ALLOWED_STATUSES.containsAll(statuses)) {
        throw new IllegalArgumentException(
            "statuses must be a subset of the allowed appointment statuses");
      }
      statuses = Set.copyOf(statuses);
    }
    if (patientId == null && practitionerId == null && startFrom == null && startTo == null) {
      throw new IllegalArgumentException(
          "At least one of patientId, practitionerId, startFrom or startTo is required");
    }
    if ((afterStartAt == null) != (afterId == null)) {
      throw new IllegalArgumentException("afterStartAt and afterId must be set together");
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be >= 1");
    }
  }

  /** Projected row from {@code scheduling.appointment} used by S12 read and S13 search. */
  public record AppointmentRow(
      UUID id,
      int versionId,
      Instant lastUpdated,
      String status,
      UUID slotId,
      UUID patientId,
      UUID practitionerId,
      String specialty,
      Instant startAt,
      Instant endAt,
      String content) {

    public AppointmentRow {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(lastUpdated, "lastUpdated must not be null");
      Objects.requireNonNull(status, "status must not be null");
      Objects.requireNonNull(slotId, "slotId must not be null");
      Objects.requireNonNull(patientId, "patientId must not be null");
      Objects.requireNonNull(practitionerId, "practitionerId must not be null");
      Objects.requireNonNull(specialty, "specialty must not be null");
      Objects.requireNonNull(startAt, "startAt must not be null");
      Objects.requireNonNull(endAt, "endAt must not be null");
      Objects.requireNonNull(content, "content must not be null");
    }
  }
}
