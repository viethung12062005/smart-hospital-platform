package org.shp.scheduling.app;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Slot;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.scheduling.domain.ScheduleTemplateRecord;
import org.shp.scheduling.domain.SlotRecord;
import org.shp.scheduling.domain.SpecialtyCatalog;
import org.shp.scheduling.infra.ScheduleRepository;
import org.shp.scheduling.infra.ScheduleTemplateRepository;
import org.shp.scheduling.infra.SlotRepository;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates FHIR {@code Slot} rows from a schedule's weekly templates in {@code Asia/Ho_Chi_Minh}.
 */
@Service
public class SlotGenerator {

  public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private final ScheduleRepository scheduleRepository;
  private final ScheduleTemplateRepository templateRepository;
  private final SlotRepository slotRepository;
  private final Clock clock;

  @Autowired
  public SlotGenerator(
      ScheduleRepository scheduleRepository,
      ScheduleTemplateRepository templateRepository,
      SlotRepository slotRepository,
      Clock clock) {
    this.scheduleRepository =
        Objects.requireNonNull(scheduleRepository, "scheduleRepository must not be null");
    this.templateRepository =
        Objects.requireNonNull(templateRepository, "templateRepository must not be null");
    this.slotRepository = Objects.requireNonNull(slotRepository, "slotRepository must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public SlotGenerator(
      ScheduleRepository scheduleRepository,
      ScheduleTemplateRepository templateRepository,
      SlotRepository slotRepository) {
    this(scheduleRepository, templateRepository, slotRepository, Clock.systemUTC());
  }

  public record SlotGenerationResult(int created, int skippedExisting) {}

  /**
   * Generate examination slots for {@code scheduleId} over [{@code from}, {@code to}] inclusive.
   * Existing or overlapping slots are skipped idempotently via {@code ON CONFLICT DO NOTHING}.
   */
  @Transactional
  public SlotGenerationResult generate(UUID scheduleId, LocalDate from, LocalDate to) {
    Objects.requireNonNull(scheduleId, "scheduleId must not be null");
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    if (to.isBefore(from)) {
      throw new ShpException("validation-failed");
    }

    ScheduleRecord schedule =
        scheduleRepository.findById(scheduleId).orElseThrow(() -> new ShpException("not-found"));
    if (!schedule.active()) {
      throw new ShpException("invalid-state-transition");
    }

    List<ScheduleTemplateRecord> templates = templateRepository.findByScheduleId(scheduleId);
    Instant now = clock.instant();
    TimeZone vnTz = TimeZone.getTimeZone(VN_ZONE);

    int created = 0;
    int skippedExisting = 0;

    for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
      int weekday = date.getDayOfWeek().getValue();
      for (ScheduleTemplateRecord template : templates) {
        if (template.weekday() != weekday
            || date.isBefore(template.validFrom())
            || (template.validTo() != null && date.isAfter(template.validTo()))) {
          continue;
        }

        LocalTime cursor = template.startTime();
        while (!cursor.plusMinutes(template.slotMinutes()).isAfter(template.endTime())) {
          LocalTime slotEnd = cursor.plusMinutes(template.slotMinutes());
          Instant startAt = ZonedDateTime.of(date, cursor, VN_ZONE).toInstant();
          Instant endAt = ZonedDateTime.of(date, slotEnd, VN_ZONE).toInstant();

          SlotRecord slotRecord = buildFreeSlot(schedule, startAt, endAt, now, vnTz);
          if (slotRepository.insertIfNotOverlapping(slotRecord)) {
            created++;
          } else {
            skippedExisting++;
          }
          cursor = slotEnd;
        }
      }
    }

    return new SlotGenerationResult(created, skippedExisting);
  }

  private static SlotRecord buildFreeSlot(
      ScheduleRecord schedule, Instant startAt, Instant endAt, Instant now, TimeZone vnTz) {
    UUID slotId = UUID.randomUUID();
    Slot fhirSlot = new Slot();
    fhirSlot.setId("Slot/" + slotId);
    fhirSlot.getMeta().setVersionId("1").setLastUpdated(Date.from(now));
    fhirSlot
        .addSpecialty()
        .addCoding()
        .setSystem(SpecialtyCatalog.SYSTEM)
        .setCode(schedule.specialty());
    fhirSlot.setSchedule(new Reference("Schedule/" + schedule.id()));
    fhirSlot.setStatus(Slot.SlotStatus.FREE);
    fhirSlot.setStartElement(
        new InstantType(Date.from(startAt), TemporalPrecisionEnum.SECOND, vnTz));
    fhirSlot.setEndElement(new InstantType(Date.from(endAt), TemporalPrecisionEnum.SECOND, vnTz));
    String content = FhirJson.serialize(fhirSlot);

    return new SlotRecord(
        slotId,
        1,
        now,
        schedule.id(),
        schedule.practitionerId(),
        schedule.specialty(),
        "free",
        startAt,
        endAt,
        content);
  }
}
