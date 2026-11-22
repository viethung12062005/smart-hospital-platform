package org.shp.scheduling.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hl7.fhir.r4.model.Slot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.scheduling.domain.ScheduleTemplateRecord;
import org.shp.scheduling.domain.SlotRecord;
import org.shp.scheduling.infra.ScheduleRepository;
import org.shp.scheduling.infra.ScheduleTemplateRepository;
import org.shp.scheduling.infra.SlotRepository;
import org.shp.web.errors.ShpException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-06 (US-011): Integration tests for {@link SlotGenerator} on PostgreSQL 16. Covers TC-AC-02.1
 * (14 free slots from 08:00-11:30 / 15 min in Asia/Ho_Chi_Minh), TC-AC-GS.1 (idempotent rerun with
 * ON CONFLICT DO NOTHING), busy-slot preservation, validity bounds, and schedule state checks.
 */
@Testcontainers
class SlotGeneratorTest {

  @Container
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  private static JdbcClient jdbc;
  private static ScheduleRepository scheduleRepository;
  private static ScheduleTemplateRepository templateRepository;
  private static SlotRepository slotRepository;
  private static SlotGenerator slotGenerator;

  private static final Instant FIXED_NOW = Instant.parse("2026-11-30T10:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  @BeforeAll
  static void setup() {
    var ds =
        new SimpleDriverDataSource(
            new org.postgresql.Driver(), DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());

    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration", "classpath:db/shp-messaging/postgres")
        .load()
        .migrate();

    jdbc = JdbcClient.create(ds);
    scheduleRepository = new ScheduleRepository(jdbc, FIXED_CLOCK);
    templateRepository = new ScheduleTemplateRepository(jdbc, FIXED_CLOCK);
    slotRepository = new SlotRepository(jdbc);
    slotGenerator =
        new SlotGenerator(scheduleRepository, templateRepository, slotRepository, FIXED_CLOCK);
  }

  @BeforeEach
  void cleanTables() {
    jdbc.sql(
            "TRUNCATE scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history CASCADE")
        .update();
  }

  private ScheduleRecord createSchedule(boolean active, String specialty) {
    UUID id = UUID.randomUUID();
    var record =
        new ScheduleRecord(
            id,
            1,
            FIXED_NOW,
            active,
            UUID.randomUUID(),
            specialty,
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"resourceType\":\"Schedule\",\"id\":\"" + id + "\"}");
    return scheduleRepository.create(record, "nurse-1");
  }

  @Test
  @DisplayName(
      "TC-AC-02.1: 08:00-11:30 / 15 min generates exactly 14 free slots in Asia/Ho_Chi_Minh")
  void template0800To1130With15MinSlotsGenerates14FreeSlots() {
    ScheduleRecord schedule = createSchedule(true, "cardiology");
    templateRepository.replaceAll(
        schedule.id(),
        List.of(
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                schedule.id(),
                1, // Monday
                LocalTime.of(8, 0),
                LocalTime.of(11, 30),
                15,
                LocalDate.of(2026, 12, 1),
                null,
                FIXED_NOW,
                "nurse-1")),
        "nurse-1");

    LocalDate monday = LocalDate.of(2026, 12, 7);
    var result = slotGenerator.generate(schedule.id(), monday, monday);

    assertThat(result.created()).isEqualTo(14);
    assertThat(result.skippedExisting()).isZero();

    List<SlotRecord> slots = slotRepository.findByScheduleId(schedule.id());
    assertThat(slots).hasSize(14);
    assertThat(slots).allSatisfy(s -> assertThat(s.status()).isEqualTo("free"));

    SlotRecord first = slots.getFirst();
    assertThat(first.startAt()).isEqualTo(Instant.parse("2026-12-07T01:00:00Z"));
    assertThat(first.endAt()).isEqualTo(Instant.parse("2026-12-07T01:15:00Z"));
    assertThat(first.content()).contains("+07:00");

    Slot parsedFirst = FhirJson.parse(first.content(), Slot.class);
    assertThat(parsedFirst.getStatus()).isEqualTo(Slot.SlotStatus.FREE);
    assertThat(parsedFirst.getStartElement().getValueAsString()).endsWith("+07:00");
    assertThat(parsedFirst.getEndElement().getValueAsString()).endsWith("+07:00");
    assertThat(parsedFirst.getSchedule().getReference()).isEqualTo("Schedule/" + schedule.id());

    SlotRecord last = slots.getLast();
    assertThat(last.startAt()).isEqualTo(Instant.parse("2026-12-07T04:15:00Z"));
    assertThat(last.endAt()).isEqualTo(Instant.parse("2026-12-07T04:30:00Z"));
  }

  @Test
  @DisplayName("TC-AC-GS.1: Regenerating the same date creates zero duplicates (idempotent)")
  void regeneratingSameDayCreatesNoDuplicates() {
    ScheduleRecord schedule = createSchedule(true, "cardiology");
    templateRepository.replaceAll(
        schedule.id(),
        List.of(
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                schedule.id(),
                1,
                LocalTime.of(8, 0),
                LocalTime.of(11, 30),
                15,
                LocalDate.of(2026, 12, 1),
                null,
                FIXED_NOW,
                "nurse-1")),
        "nurse-1");

    LocalDate monday = LocalDate.of(2026, 12, 7);
    var firstRun = slotGenerator.generate(schedule.id(), monday, monday);
    assertThat(firstRun.created()).isEqualTo(14);
    assertThat(firstRun.skippedExisting()).isZero();

    var secondRun = slotGenerator.generate(schedule.id(), monday, monday);
    assertThat(secondRun.created()).isZero();
    assertThat(secondRun.skippedExisting()).isEqualTo(14);

    assertThat(slotRepository.findByScheduleId(schedule.id())).hasSize(14);
  }

  @Test
  @DisplayName("Existing busy slot is skipped via ON CONFLICT DO NOTHING and keeps busy status")
  void existingBusySlotIsSkippedAndPreserved() {
    ScheduleRecord schedule = createSchedule(true, "cardiology");
    templateRepository.replaceAll(
        schedule.id(),
        List.of(
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                schedule.id(),
                1,
                LocalTime.of(8, 0),
                LocalTime.of(11, 30),
                15,
                LocalDate.of(2026, 12, 1),
                null,
                FIXED_NOW,
                "nurse-1")),
        "nurse-1");

    // Pre-insert a busy slot at 08:30-08:45 VN (01:30-01:45 UTC)
    UUID existingBusyId = UUID.randomUUID();
    SlotRecord busySlot =
        new SlotRecord(
            existingBusyId,
            2,
            FIXED_NOW,
            schedule.id(),
            schedule.practitionerId(),
            "cardiology",
            "busy",
            Instant.parse("2026-12-07T01:30:00Z"),
            Instant.parse("2026-12-07T01:45:00Z"),
            "{\"resourceType\":\"Slot\",\"id\":\"" + existingBusyId + "\",\"status\":\"busy\"}");
    assertThat(slotRepository.insertIfNotOverlapping(busySlot)).isTrue();

    LocalDate monday = LocalDate.of(2026, 12, 7);
    var result = slotGenerator.generate(schedule.id(), monday, monday);
    assertThat(result.created()).isEqualTo(13);
    assertThat(result.skippedExisting()).isEqualTo(1);

    List<SlotRecord> slots = slotRepository.findByScheduleId(schedule.id());
    assertThat(slots).hasSize(14);
    var loadedBusy = slotRepository.findById(existingBusyId);
    assertThat(loadedBusy).isPresent();
    assertThat(loadedBusy.get().status()).isEqualTo("busy");
  }

  @Test
  @DisplayName("Multi-day generation respects template weekdays and validFrom/validTo dates")
  void multiDayGenerationRespectsWeekdaysAndValidityDates() {
    ScheduleRecord schedule = createSchedule(true, "pediatrics");
    templateRepository.replaceAll(
        schedule.id(),
        List.of(
            // Monday: 08:00-09:00 / 30 min -> 2 slots/day, valid up to 2026-12-07
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                schedule.id(),
                1,
                LocalTime.of(8, 0),
                LocalTime.of(9, 0),
                30,
                LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 7),
                FIXED_NOW,
                "nurse-1"),
            // Wednesday: 13:30-14:30 / 30 min -> 2 slots/day, open-ended validTo
            new ScheduleTemplateRecord(
                UUID.randomUUID(),
                schedule.id(),
                3,
                LocalTime.of(13, 30),
                LocalTime.of(14, 30),
                30,
                LocalDate.of(2026, 12, 1),
                null,
                FIXED_NOW,
                "nurse-1")),
        "nurse-1");

    // 2026-12-01 (Tue) .. 2026-12-14 (Mon): Mon 12-07 (2), Wed 12-02 (2), Wed 12-09 (2);
    // Mon 12-14 is excluded because it exceeds validTo=2026-12-07.
    var result =
        slotGenerator.generate(
            schedule.id(), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 14));

    assertThat(result.created()).isEqualTo(6);
    assertThat(result.skippedExisting()).isZero();
    assertThat(slotRepository.findByScheduleId(schedule.id())).hasSize(6);
  }

  @Test
  @DisplayName("Non-existent or inactive schedule raises the expected error")
  void inactiveOrNonExistentScheduleThrowsExpectedError() {
    UUID missingId = UUID.randomUUID();
    LocalDate day = LocalDate.of(2026, 12, 7);

    assertThatThrownBy(() -> slotGenerator.generate(missingId, day, day))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("not-found"));

    ScheduleRecord inactive = createSchedule(false, "dermatology");
    assertThatThrownBy(() -> slotGenerator.generate(inactive.id(), day, day))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> assertThat(e.info().code()).isEqualTo("invalid-state-transition"));
  }
}
