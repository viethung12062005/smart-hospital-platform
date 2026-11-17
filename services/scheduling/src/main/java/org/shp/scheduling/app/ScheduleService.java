package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Schedule;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.domain.PractitionerRoleClient;
import org.shp.scheduling.domain.ScheduleFilter;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.scheduling.domain.SpecialtyCatalog;
import org.shp.scheduling.infra.ScheduleRepository;
import org.shp.web.errors.ShpException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for doctor work schedules (FHIR Schedule). */
@Service
public class ScheduleService {

  private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private final ScheduleRepository repository;
  private final PractitionerRoleClient practitionerRoleClient;
  private final Clock clock;

  public ScheduleService(
      ScheduleRepository repository, PractitionerRoleClient practitionerRoleClient, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.practitionerRoleClient =
        Objects.requireNonNull(practitionerRoleClient, "practitionerRoleClient must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** S1: Create a doctor's work schedule. */
  @Transactional
  public ScheduleRecord createSchedule(Schedule schedule, String changedBy) {
    Objects.requireNonNull(schedule, "schedule must not be null");
    if (changedBy == null || changedBy.isBlank()) {
      changedBy = "system";
    }

    // 1. actor must be exactly 1 element pointing to Practitioner/<uuid>
    if (schedule.getActor().size() != 1) {
      throw new ShpException("validation-failed");
    }
    String actorRef = schedule.getActorFirstRep().getReference();
    UUID practitionerId = parsePractitionerId(actorRef);

    // 2. specialty must have at least 1 coding in SpecialtyCatalog
    if (schedule.getSpecialty().isEmpty()
        || schedule.getSpecialtyFirstRep().getCoding().isEmpty()) {
      throw new ShpException("validation-failed");
    }
    String specialtyCode = schedule.getSpecialtyFirstRep().getCodingFirstRep().getCode();
    if (specialtyCode == null || !SpecialtyCatalog.isValid(specialtyCode)) {
      throw new ShpException("validation-failed");
    }
    specialtyCode = specialtyCode.strip().toLowerCase(java.util.Locale.ROOT);

    // 3. Practitioner must exist and have an active role matching the specialty
    if (!practitionerRoleClient.hasActiveSpecialty(practitionerId, specialtyCode)) {
      throw new ShpException("validation-failed");
    }

    // 4. Server assigns id and planningHorizon (today -> +30 days in Asia/Ho_Chi_Minh)
    UUID id = UUID.randomUUID();
    LocalDate today = LocalDate.ofInstant(clock.instant(), VN_ZONE);
    LocalDate horizonEnd = today.plusDays(30);

    Period horizon = new Period();
    horizon.setStart(Date.from(today.atStartOfDay(VN_ZONE).toInstant()));
    horizon.setEnd(Date.from(horizonEnd.atTime(23, 59, 59).atZone(VN_ZONE).toInstant()));
    schedule.setPlanningHorizon(horizon);

    schedule.setId("Schedule/" + id);
    Instant now = clock.instant();
    schedule.getMeta().setVersionId("1").setLastUpdated(Date.from(now));

    String content = FhirJson.serialize(schedule);
    var record =
        new ScheduleRecord(
            id,
            1,
            now,
            schedule.getActive(),
            practitionerId,
            specialtyCode,
            today,
            horizonEnd,
            content);

    try {
      return repository.create(record, changedBy);
    } catch (DataIntegrityViolationException e) {
      // Duplicate active schedule for (practitioner_id, specialty) -> 409
      throw new ShpException("invalid-state-transition");
    }
  }

  /** S2: Read a schedule by ID. */
  @Transactional(readOnly = true)
  public ScheduleRecord getSchedule(UUID id) {
    return repository.findById(id).orElseThrow(() -> new ShpException("not-found"));
  }

  /** S3: Search schedules by criteria. */
  @Transactional(readOnly = true)
  public List<ScheduleRecord> searchSchedules(
      UUID practitionerId, String specialty, Boolean active, int limit, int offset) {
    return repository.find(new ScheduleFilter(practitionerId, specialty, active, limit, offset));
  }

  /** S4: Edit a schedule (enable/disable, notes). */
  @Transactional
  public ScheduleRecord updateSchedule(
      UUID id, Schedule inputSchedule, long expectedVersion, String changedBy) {
    Objects.requireNonNull(inputSchedule, "inputSchedule must not be null");
    if (changedBy == null || changedBy.isBlank()) {
      changedBy = "system";
    }

    var existing = repository.findById(id).orElseThrow(() -> new ShpException("not-found"));

    // Check ID consistency if present in body
    if (inputSchedule.getIdElement().hasIdPart()) {
      String idPart = inputSchedule.getIdElement().getIdPart();
      if (!idPart.equalsIgnoreCase(id.toString()) && !idPart.equalsIgnoreCase("Schedule/" + id)) {
        throw new ShpException("validation-failed");
      }
    }

    // S4 edits only active/comment: the Schedule keeps its doctor and specialty (the row columns
    // and the stored resource must not diverge), and active must be stated explicitly.
    if (!inputSchedule.hasActiveElement()) {
      throw new ShpException("validation-failed");
    }
    if (inputSchedule.getActor().size() != 1
        || !existing
            .practitionerId()
            .equals(parsePractitionerId(inputSchedule.getActorFirstRep().getReference()))) {
      throw new ShpException("validation-failed");
    }
    if (inputSchedule.getSpecialty().isEmpty()
        || inputSchedule.getSpecialtyFirstRep().getCoding().isEmpty()
        || !existing
            .specialty()
            .equalsIgnoreCase(
                String.valueOf(inputSchedule.getSpecialtyFirstRep().getCodingFirstRep().getCode())
                    .strip())) {
      throw new ShpException("validation-failed");
    }

    int nextVersion = (int) expectedVersion + 1;
    Instant now = clock.instant();

    inputSchedule.setId("Schedule/" + id);
    inputSchedule
        .getMeta()
        .setVersionId(String.valueOf(nextVersion))
        .setLastUpdated(Date.from(now));

    // Preserve horizon from existing if not provided
    if (!inputSchedule.hasPlanningHorizon()) {
      Period horizon = new Period();
      horizon.setStart(Date.from(existing.horizonStart().atStartOfDay(VN_ZONE).toInstant()));
      horizon.setEnd(
          Date.from(existing.horizonEnd().atTime(23, 59, 59).atZone(VN_ZONE).toInstant()));
      inputSchedule.setPlanningHorizon(horizon);
    }

    String content = FhirJson.serialize(inputSchedule);
    var updatedRecord =
        new ScheduleRecord(
            id,
            nextVersion,
            now,
            inputSchedule.getActive(),
            existing.practitionerId(),
            existing.specialty(),
            existing.horizonStart(),
            existing.horizonEnd(),
            content);

    try {
      return repository.update(updatedRecord, (int) expectedVersion, changedBy);
    } catch (DataIntegrityViolationException e) {
      throw new ShpException("invalid-state-transition");
    }
  }

  public static UUID parsePractitionerId(String reference) {
    if (reference == null || reference.isBlank()) {
      throw new ShpException("validation-failed");
    }
    String raw = reference.trim();
    if (raw.startsWith("Practitioner/")) {
      raw = raw.substring("Practitioner/".length());
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ShpException("validation-failed");
    }
  }
}
