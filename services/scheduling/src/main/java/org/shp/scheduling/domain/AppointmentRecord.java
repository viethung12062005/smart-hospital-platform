package org.shp.scheduling.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A booked appointment (FHIR Appointment); corresponds to table {@code scheduling.appointment}. */
public record AppointmentRecord(
    UUID id,
    int versionId,
    Instant lastUpdated,
    String status,
    UUID slotId,
    UUID patientId,
    UUID practitionerId,
    String specialty,
    Instant start,
    Instant end,
    String channel,
    String bookedBy,
    String content) {

  public AppointmentRecord {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(lastUpdated, "lastUpdated must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(slotId, "slotId must not be null");
    Objects.requireNonNull(patientId, "patientId must not be null");
    Objects.requireNonNull(practitionerId, "practitionerId must not be null");
    Objects.requireNonNull(specialty, "specialty must not be null");
    Objects.requireNonNull(start, "start must not be null");
    Objects.requireNonNull(end, "end must not be null");
    Objects.requireNonNull(channel, "channel must not be null");
    Objects.requireNonNull(bookedBy, "bookedBy must not be null");
    Objects.requireNonNull(content, "content must not be null");
  }

  /** Same appointment with the stored FHIR content replaced. */
  public AppointmentRecord withContent(String newContent) {
    return new AppointmentRecord(
        id,
        versionId,
        lastUpdated,
        status,
        slotId,
        patientId,
        practitionerId,
        specialty,
        start,
        end,
        channel,
        bookedBy,
        newContent);
  }
}
