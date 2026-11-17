package org.shp.scheduling.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
import org.shp.scheduling.domain.ScheduleTemplateRecord;
import org.shp.scheduling.domain.SpecialtyCatalog;
import org.shp.scheduling.infra.ScheduleRepository;
import org.shp.scheduling.infra.ScheduleTemplateRepository;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for doctor work schedules (FHIR Schedule) and weekly schedule templates. */
@Service
public class ScheduleService {

  private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private final ScheduleRepository repository;
  private final ScheduleTemplateRepository templateRepository;
  private final PractitionerRoleClient practitionerRoleClient;
  private final Clock clock;

  @Autowired
  public ScheduleService(
      ScheduleRepository repository,
      ScheduleTemplateRepository templateRepository,
      PractitionerRoleClient practitionerRoleClient,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.templateRepository =
        Objects.requireNonNull(templateRepository, "templateRepository must not be null");
    this.practitionerRoleClient =
        Objects.requireNonNull(practitionerRoleClient, "practitionerRoleClient must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public record TemplateInput(
      Integer weekday,
      String startTime,
      String endTime,
      Integer slotMinutes,
      String validFrom,
      String validTo) {}

  public record ReplaceTemplatesResult(
      List<ScheduleTemplateRecord> templates, int scheduleVersion, int affectedFutureSlots) {}

  public record GetTemplatesResult(List<ScheduleTemplateRecord> templates, int scheduleVersion) {}

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

  /**
   * S3: Search schedules by criteria, keyset-paged by id. Returns at most {@code limit} records
   * after {@code afterId}.
   */
  @Transactional(readOnly = true)
  public List<ScheduleRecord> searchSchedules(
      UUID practitionerId, String specialty, Boolean active, int limit, UUID afterId) {
    return repository.find(new ScheduleFilter(practitionerId, specialty, active, limit, afterId));
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

  /** S5: Replace a schedule's entire weekly template set in one transaction with CAS on version. */
  @Transactional
  public ReplaceTemplatesResult replaceTemplates(
      UUID scheduleId, List<TemplateInput> inputs, long expectedVersion, String changedBy) {
    Objects.requireNonNull(scheduleId, "scheduleId must not be null");
    String actor = (changedBy == null || changedBy.isBlank()) ? "system" : changedBy;
    Instant now = clock.instant();

    List<ScheduleTemplateRecord> records =
        validateAndBuildTemplates(scheduleId, inputs, now, actor);

    ScheduleRecord existing =
        repository.findById(scheduleId).orElseThrow(() -> new ShpException("not-found"));

    int nextVersion = (int) expectedVersion + 1;
    Schedule fhirSchedule = FhirJson.parse(existing.content(), Schedule.class);
    fhirSchedule.getMeta().setVersionId(String.valueOf(nextVersion)).setLastUpdated(Date.from(now));
    String updatedContent = FhirJson.serialize(fhirSchedule);

    var updatedScheduleRecord =
        new ScheduleRecord(
            existing.id(),
            nextVersion,
            now,
            existing.active(),
            existing.practitionerId(),
            existing.specialty(),
            existing.horizonStart(),
            existing.horizonEnd(),
            updatedContent);

    ScheduleRecord savedSchedule =
        repository.update(updatedScheduleRecord, (int) expectedVersion, actor);
    List<ScheduleTemplateRecord> savedTemplates =
        templateRepository.replaceAll(scheduleId, records, actor);

    return new ReplaceTemplatesResult(savedTemplates, savedSchedule.versionId(), 0);
  }

  /** S6: Retrieve a schedule's weekly template rows along with the schedule version. */
  @Transactional(readOnly = true)
  public GetTemplatesResult getTemplates(UUID scheduleId) {
    Objects.requireNonNull(scheduleId, "scheduleId must not be null");
    ScheduleRecord existing =
        repository.findById(scheduleId).orElseThrow(() -> new ShpException("not-found"));
    List<ScheduleTemplateRecord> templates = templateRepository.findByScheduleId(scheduleId);
    return new GetTemplatesResult(templates, existing.versionId());
  }

  private static List<ScheduleTemplateRecord> validateAndBuildTemplates(
      UUID scheduleId, List<TemplateInput> inputs, Instant now, String changedBy) {
    if (inputs == null) {
      throw new ShpException("validation-failed");
    }

    List<ScheduleTemplateRecord> result = new ArrayList<>(inputs.size());
    for (TemplateInput item : inputs) {
      if (item == null
          || item.weekday() == null
          || item.startTime() == null
          || item.endTime() == null
          || item.slotMinutes() == null
          || item.validFrom() == null
          || item.validFrom().isBlank()) {
        throw new ShpException("validation-failed");
      }

      int weekday = item.weekday();
      if (weekday < 1 || weekday > 7) {
        throw new ShpException("validation-failed");
      }

      int slotMinutes = item.slotMinutes();
      if (!ScheduleTemplateRecord.ALLOWED_SLOT_MINUTES.contains(slotMinutes)) {
        throw new ShpException("validation-failed");
      }

      LocalTime startTime = parseMinuteAlignedTime(item.startTime());
      LocalTime endTime = parseMinuteAlignedTime(item.endTime());
      if (!startTime.isBefore(endTime)) {
        throw new ShpException("validation-failed");
      }

      long totalMinutes = Duration.between(startTime, endTime).toMinutes();
      if (totalMinutes <= 0 || totalMinutes % slotMinutes != 0) {
        throw new ShpException("validation-failed");
      }

      LocalDate validFrom = parseIsoDate(item.validFrom());
      LocalDate validTo = null;
      if (item.validTo() != null && !item.validTo().isBlank()) {
        validTo = parseIsoDate(item.validTo());
        if (validTo.isBefore(validFrom)) {
          throw new ShpException("validation-failed");
        }
      }

      result.add(
          new ScheduleTemplateRecord(
              UUID.randomUUID(),
              scheduleId,
              weekday,
              startTime,
              endTime,
              slotMinutes,
              validFrom,
              validTo,
              now,
              changedBy));
    }

    // Check overlapping intervals on the same weekday: [a.start, a.end) overlaps [b.start, b.end)
    for (int i = 0; i < result.size(); i++) {
      ScheduleTemplateRecord a = result.get(i);
      for (int j = i + 1; j < result.size(); j++) {
        ScheduleTemplateRecord b = result.get(j);
        if (a.weekday() == b.weekday()
            && a.startTime().isBefore(b.endTime())
            && b.startTime().isBefore(a.endTime())) {
          throw new ShpException("validation-failed");
        }
      }
    }

    return result;
  }

  private static LocalTime parseMinuteAlignedTime(String raw) {
    try {
      LocalTime parsed = LocalTime.parse(raw.strip());
      if (parsed.getSecond() != 0 || parsed.getNano() != 0) {
        throw new ShpException("validation-failed");
      }
      return parsed;
    } catch (DateTimeParseException e) {
      throw new ShpException("validation-failed");
    }
  }

  private static LocalDate parseIsoDate(String raw) {
    try {
      return LocalDate.parse(raw.strip());
    } catch (DateTimeParseException e) {
      throw new ShpException("validation-failed");
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
