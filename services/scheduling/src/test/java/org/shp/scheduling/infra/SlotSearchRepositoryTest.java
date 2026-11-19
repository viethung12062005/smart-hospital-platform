package org.shp.scheduling.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.scheduling.domain.SlotRecord;
import org.shp.scheduling.domain.SlotSearchCriteria;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-09 (US-014): slot search on PostgreSQL 16 - filters, stable keyset pagination ordered by
 * (start_at, id), and the use of {@code ix_slot_free_by_specialty} proved with EXPLAIN.
 */
@Testcontainers
class SlotSearchRepositoryTest {

  @Container
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  private static final Instant NOW = Instant.parse("2026-11-30T10:00:00Z");
  private static final Instant DAY1 = Instant.parse("2026-12-01T01:00:00Z");
  private static final Set<String> FREE = Set.of("free");

  private static JdbcClient jdbc;
  private static ScheduleRepository scheduleRepository;
  private static SlotSearchRepository repository;

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
    scheduleRepository = new ScheduleRepository(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    repository = new SlotSearchRepository(jdbc);
  }

  @BeforeEach
  void clean() {
    jdbc.sql(
            "TRUNCATE scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history CASCADE")
        .update();
  }

  private ScheduleRecord schedule(String specialty) {
    UUID id = UUID.randomUUID();
    return scheduleRepository.create(
        new ScheduleRecord(
            id,
            1,
            NOW,
            true,
            UUID.randomUUID(),
            specialty,
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"resourceType\":\"Schedule\",\"id\":\"" + id + "\"}"),
        "nurse-1");
  }

  /** {@code count} consecutive 15-minute slots from {@code first}, with the given status. */
  private void slots(ScheduleRecord s, Instant first, int count, String status) {
    jdbc.sql(
            """
            INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id,
                                         specialty, status, start_at, end_at, content)
            SELECT gen_random_uuid(), 1, now(), ?, ?, ?, ?,
                   ?::timestamptz + (g * interval '15 minutes'),
                   ?::timestamptz + ((g + 1) * interval '15 minutes'), '{}'::jsonb
              FROM generate_series(0, ? - 1) AS g
            """)
        .params(
            s.id(),
            s.practitionerId(),
            s.specialty(),
            status,
            java.sql.Timestamp.from(first),
            java.sql.Timestamp.from(first),
            count)
        .update();
  }

  private static SlotSearchCriteria bySpecialty(String specialty, int limit) {
    return new SlotSearchCriteria(FREE, specialty, null, null, null, null, null, null, limit);
  }

  @Test
  @DisplayName("filters by specialty, practitioner, schedule, status and the start range")
  void appliesEveryFilter() {
    ScheduleRecord cardio = schedule("cardiology");
    ScheduleRecord derm = schedule("dermatology");
    slots(cardio, DAY1, 8, "free");
    slots(cardio, DAY1.plusSeconds(8 * 900), 2, "busy");
    slots(derm, DAY1, 4, "free");

    assertThat(repository.search(bySpecialty("cardiology", 50))).hasSize(8);
    assertThat(repository.search(bySpecialty("dermatology", 50)))
        .extracting(SlotRecord::scheduleId)
        .containsOnly(derm.id());

    var byDoctor =
        new SlotSearchCriteria(
            FREE, null, cardio.practitionerId(), null, null, null, null, null, 50);
    assertThat(repository.search(byDoctor)).hasSize(8);

    var bySchedule =
        new SlotSearchCriteria(FREE, null, null, derm.id(), null, null, null, null, 50);
    assertThat(repository.search(bySchedule)).hasSize(4);

    var busy =
        new SlotSearchCriteria(
            Set.of("busy"), "cardiology", null, null, null, null, null, null, 50);
    assertThat(repository.search(busy)).hasSize(2);

    // [DAY1 + 30 min, DAY1 + 60 min): the 3rd and 4th slots
    var range =
        new SlotSearchCriteria(
            FREE,
            "cardiology",
            null,
            null,
            DAY1.plusSeconds(1800),
            DAY1.plusSeconds(3600),
            null,
            null,
            50);
    assertThat(repository.search(range))
        .extracting(SlotRecord::startAt)
        .containsExactly(DAY1.plusSeconds(1800), DAY1.plusSeconds(2700));
  }

  @Test
  @DisplayName("keyset pages are stable: no gaps or repeats, ties on start_at ordered by id")
  void keysetPaginationIsStable() {
    // Two doctors with the same specialty share every start_at, so the id tie-break matters.
    ScheduleRecord a = schedule("cardiology");
    ScheduleRecord b = schedule("cardiology");
    slots(a, DAY1, 13, "free");
    slots(b, DAY1, 12, "free");

    List<SlotRecord> all = repository.search(bySpecialty("cardiology", 100));
    assertThat(all).hasSize(25);

    List<SlotRecord> paged = new ArrayList<>();
    Instant afterStart = null;
    UUID afterId = null;
    int pages = 0;
    while (true) {
      var page =
          repository.search(
              new SlotSearchCriteria(
                  FREE, "cardiology", null, null, null, null, afterStart, afterId, 10));
      pages++;
      paged.addAll(page);
      if (page.size() < 10) {
        break;
      }
      afterStart = page.getLast().startAt();
      afterId = page.getLast().id();
    }

    assertThat(pages).isEqualTo(3);
    assertThat(paged)
        .extracting(SlotRecord::id)
        .containsExactlyElementsOf(all.stream().map(SlotRecord::id).toList());
    assertThat(new HashSet<>(paged.stream().map(SlotRecord::id).toList())).hasSize(25);
    for (int i = 1; i < paged.size(); i++) {
      var prev = paged.get(i - 1);
      var cur = paged.get(i);
      assertThat(cur.startAt()).isAfterOrEqualTo(prev.startAt());
      if (cur.startAt().equals(prev.startAt())) {
        // PostgreSQL orders uuid by unsigned bytes, which is the lexicographic order of the text
        // form
        assertThat(cur.id().toString().compareTo(prev.id().toString())).isPositive();
      }
    }

    // A slot booked between two requests does not shift the next page.
    var firstPage = repository.search(bySpecialty("cardiology", 10));
    jdbc.sql("UPDATE scheduling.slot SET status = 'busy' WHERE id = ?")
        .param(firstPage.get(3).id())
        .update();
    var secondPage =
        repository.search(
            new SlotSearchCriteria(
                FREE,
                "cardiology",
                null,
                null,
                null,
                null,
                firstPage.getLast().startAt(),
                firstPage.getLast().id(),
                10));
    assertThat(secondPage)
        .extracting(SlotRecord::id)
        .isEqualTo(all.subList(10, 20).stream().map(SlotRecord::id).toList());
  }

  @Test
  @DisplayName("the free-slot search uses ix_slot_free_by_specialty (EXPLAIN)")
  void freeSearchUsesThePartialIndex() {
    String[] specialties = {
      "cardiology", "dermatology", "pediatrics", "general-medicine", "neurology", "oncology"
    };
    for (String specialty : specialties) {
      ScheduleRecord s = schedule(specialty);
      slots(s, DAY1, 4000, "free");
      slots(s, DAY1.plusSeconds(4000L * 900), 2000, "busy");
    }
    jdbc.sql("ANALYZE scheduling.slot").update();

    var criteria =
        new SlotSearchCriteria(
            FREE, "cardiology", null, null, DAY1, DAY1.plusSeconds(7L * 24 * 3600), null, null, 20);
    String plan = repository.explain(criteria);

    assertThat(plan).contains("ix_slot_free_by_specialty");
    assertThat(plan).doesNotContain("Seq Scan");
    assertThat(repository.search(criteria)).hasSize(20);
  }

  @Test
  @DisplayName("rejects criteria without a filter, with half a cursor, or a bad status or limit")
  void rejectsInvalidCriteria() {
    assertThatThrownBy(
            () -> new SlotSearchCriteria(FREE, null, null, null, null, null, null, null, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new SlotSearchCriteria(FREE, "cardiology", null, null, null, null, DAY1, null, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new SlotSearchCriteria(
                Set.of("x'; DROP TABLE slot; --"),
                "cardiology",
                null,
                null,
                null,
                null,
                null,
                null,
                10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> bySpecialty("cardiology", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
