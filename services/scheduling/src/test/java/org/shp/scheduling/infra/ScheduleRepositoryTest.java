package org.shp.scheduling.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.scheduling.domain.ScheduleFilter;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.web.errors.ShpException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * SCH-03 (US-009): Integration test for ScheduleRepository on real PostgreSQL 16. Verifies create,
 * read, find with filters, CAS optimistic locking on update, and version history tracking in
 * resource_history.
 */
@Testcontainers
class ScheduleRepositoryTest {

  @Container
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  private static JdbcClient jdbc;
  private static ScheduleRepository repository;

  private static final Instant FIXED_NOW = Instant.parse("2026-11-15T10:00:00Z");
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
    jdbc.sql("SET search_path TO scheduling, public").update();
    repository = new ScheduleRepository(jdbc, FIXED_CLOCK);
  }

  @BeforeEach
  void cleanTables() {
    jdbc.sql(
            "TRUNCATE scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history CASCADE")
        .update();
  }

  @Test
  @DisplayName(
      "create() persists schedule and writes initial resource_history (version 1, operation create)")
  void createScheduleWritesDataAndHistory() {
    UUID id = UUID.randomUUID();
    UUID practitionerId = UUID.randomUUID();
    String content =
        "{\"resourceType\":\"Schedule\",\"id\":\"" + id + "\",\"comment\":\"Clinic A\"}";

    var record =
        new ScheduleRecord(
            id,
            1,
            FIXED_NOW,
            true,
            practitionerId,
            "cardiology",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            content);

    var created = repository.create(record, "nurse-alice");

    assertThat(created.id()).isEqualTo(id);
    assertThat(created.versionId()).isEqualTo(1);
    assertThat(created.active()).isTrue();
    assertThat(created.practitionerId()).isEqualTo(practitionerId);
    assertThat(created.specialty()).isEqualTo("cardiology");
    assertThat(created.content()).isEqualTo(content);

    // Verify findById
    var found = repository.findById(id);
    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(id);
    assertThat(found.get().versionId()).isEqualTo(1);
    assertThat(found.get().specialty()).isEqualTo("cardiology");

    // Verify resource_history
    var history = repository.findHistory(id);
    assertThat(history).hasSize(1);
    var h0 = history.get(0);
    assertThat(h0.resourceType()).isEqualTo("Schedule");
    assertThat(h0.id()).isEqualTo(id);
    assertThat(h0.versionId()).isEqualTo(1);
    assertThat(h0.operation()).isEqualTo("create");
    assertThat(h0.changedBy()).isEqualTo("nurse-alice");
    assertThat(h0.content()).contains("Clinic A");
  }

  @Test
  @DisplayName("find() with filter criteria and pagination")
  void findWithFiltersAndPagination() {
    UUID docA = UUID.randomUUID();
    UUID docB = UUID.randomUUID();

    // docA: cardiology, active
    repository.create(
        new ScheduleRecord(
            UUID.randomUUID(),
            1,
            FIXED_NOW,
            true,
            docA,
            "cardiology",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"id\":\"1\"}"),
        "nurse-alice");

    // docA: pediatrics, active
    repository.create(
        new ScheduleRecord(
            UUID.randomUUID(),
            1,
            FIXED_NOW.plusSeconds(10),
            true,
            docA,
            "pediatrics",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"id\":\"2\"}"),
        "nurse-alice");

    // docB: cardiology, active
    repository.create(
        new ScheduleRecord(
            UUID.randomUUID(),
            1,
            FIXED_NOW.plusSeconds(20),
            true,
            docB,
            "cardiology",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"id\":\"3\"}"),
        "nurse-bob");

    // docB: dermatology, inactive
    repository.create(
        new ScheduleRecord(
            UUID.randomUUID(),
            1,
            FIXED_NOW.plusSeconds(30),
            false,
            docB,
            "dermatology",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"id\":\"4\"}"),
        "nurse-bob");

    // Filter by practitioner
    var docAResults = repository.find(ScheduleFilter.of(docA, null, null));
    assertThat(docAResults).hasSize(2);

    // Filter by specialty
    var cardioResults = repository.find(ScheduleFilter.of(null, "cardiology", null));
    assertThat(cardioResults).hasSize(2);

    // Filter by active
    var inactiveResults = repository.find(ScheduleFilter.of(null, null, false));
    assertThat(inactiveResults).hasSize(1);
    assertThat(inactiveResults.get(0).specialty()).isEqualTo("dermatology");

    // Filter by doctor AND specialty
    var docACardio = repository.find(ScheduleFilter.of(docA, "cardiology", null));
    assertThat(docACardio).hasSize(1);
    assertThat(docACardio.get(0).practitionerId()).isEqualTo(docA);

    // Pagination
    var page1 = repository.find(new ScheduleFilter(null, null, null, 2, null));
    assertThat(page1).hasSize(2);

    var page2 = repository.find(new ScheduleFilter(null, null, null, 2, page1.get(1).id()));
    assertThat(page2).hasSize(2);

    // Keyset order is id ascending (PostgreSQL uuid order equals lower-case hex string order): the
    // pages are disjoint and together cover all 4 rows in order.
    var ids = new java.util.ArrayList<String>();
    page1.forEach(r -> ids.add(r.id().toString()));
    page2.forEach(r -> ids.add(r.id().toString()));
    assertThat(ids).doesNotHaveDuplicates().isSorted();
    assertThat(repository.find(new ScheduleFilter(null, null, null, 2, page2.get(1).id())))
        .isEmpty();
  }

  @Test
  @DisplayName("update() with matching expectedVersion increments version_id and writes history")
  void updateWithMatchingVersionSucceeds() {
    UUID id = UUID.randomUUID();
    UUID practitionerId = UUID.randomUUID();

    var initial =
        repository.create(
            new ScheduleRecord(
                id,
                1,
                FIXED_NOW,
                true,
                practitionerId,
                "cardiology",
                LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 31),
                "{\"version\":1}"),
            "nurse-alice");

    assertThat(initial.versionId()).isEqualTo(1);

    // Update from version 1 -> 2
    String updatedContent = "{\"version\":2,\"comment\":\"Updated room\"}";
    var updated =
        repository.update(
            new ScheduleRecord(
                id,
                1,
                null,
                false,
                practitionerId,
                "cardiology",
                LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 31),
                updatedContent),
            1,
            "nurse-bob");

    assertThat(updated.versionId()).isEqualTo(2);
    assertThat(updated.active()).isFalse();
    assertThat(updated.content()).isEqualTo(updatedContent);

    // Verify persisted state
    var current = repository.findById(id).orElseThrow();
    assertThat(current.versionId()).isEqualTo(2);
    assertThat(current.active()).isFalse();

    // Verify history now has 2 entries
    var history = repository.findHistory(id);
    assertThat(history).hasSize(2);

    assertThat(history.get(0).versionId()).isEqualTo(1);
    assertThat(history.get(0).operation()).isEqualTo("create");
    assertThat(history.get(0).changedBy()).isEqualTo("nurse-alice");

    assertThat(history.get(1).versionId()).isEqualTo(2);
    assertThat(history.get(1).operation()).isEqualTo("update");
    assertThat(history.get(1).changedBy()).isEqualTo("nurse-bob");
    assertThat(history.get(1).content()).contains("Updated room");

    // Update again: version 2 -> 3
    String v3Content = "{\"version\":3,\"comment\":\"Re-enabled\"}";
    var updatedV3 =
        repository.update(
            new ScheduleRecord(
                id,
                2,
                null,
                true,
                practitionerId,
                "cardiology",
                LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 31),
                v3Content),
            2,
            "admin-charlie");

    assertThat(updatedV3.versionId()).isEqualTo(3);
    assertThat(repository.findHistory(id)).hasSize(3);
  }

  @Test
  @DisplayName(
      "update() with stale expectedVersion throws ShpException(version-conflict) (412) and does not write history")
  void updateWithStaleVersionFailsCAS() {
    UUID id = UUID.randomUUID();
    UUID practitionerId = UUID.randomUUID();

    repository.create(
        new ScheduleRecord(
            id,
            1,
            FIXED_NOW,
            true,
            practitionerId,
            "cardiology",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"version\":1}"),
        "nurse-alice");

    // Stale version: current is 1, caller provides expectedVersion = 99
    var staleUpdateRecord =
        new ScheduleRecord(
            id,
            99,
            null,
            false,
            practitionerId,
            "cardiology",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"version\":99}");

    assertThatThrownBy(() -> repository.update(staleUpdateRecord, 99, "nurse-bob"))
        .isInstanceOf(ShpException.class)
        .satisfies(ex -> {
          var shpEx = (ShpException) ex;
          assertThat(shpEx.info().code()).isEqualTo("version-conflict");
          assertThat(shpEx.info().status()).isEqualTo(412);
        });

    // Verify schedule table still has version 1 and active = true
    var current = repository.findById(id).orElseThrow();
    assertThat(current.versionId()).isEqualTo(1);
    assertThat(current.active()).isTrue();

    // Verify resource_history still only has the single 'create' entry
    var history = repository.findHistory(id);
    assertThat(history).hasSize(1);
    assertThat(history.get(0).operation()).isEqualTo("create");
  }

  @Test
  @DisplayName("update() on non-existent schedule throws ShpException(not-found) (404)")
  void updateNonExistentThrowsNotFound() {
    UUID nonExistentId = UUID.randomUUID();
    var record =
        new ScheduleRecord(
            nonExistentId,
            1,
            null,
            true,
            UUID.randomUUID(),
            "cardiology",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{}");

    assertThatThrownBy(() -> repository.update(record, 1, "nurse-alice"))
        .isInstanceOf(ShpException.class)
        .satisfies(ex -> {
          var shpEx = (ShpException) ex;
          assertThat(shpEx.info().code()).isEqualTo("not-found");
          assertThat(shpEx.info().status()).isEqualTo(404);
        });
  }

  @Test
  @DisplayName("findHistoryVersion() retrieves exact historical version")
  void findHistoryVersionReturnsTargetVersion() {
    UUID id = UUID.randomUUID();
    UUID practitionerId = UUID.randomUUID();

    repository.create(
        new ScheduleRecord(
            id,
            1,
            FIXED_NOW,
            true,
            practitionerId,
            "cardiology",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"v\":1}"),
        "nurse-alice");

    repository.update(
        new ScheduleRecord(
            id,
            1,
            null,
            true,
            practitionerId,
            "cardiology",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{\"v\":2}"),
        1,
        "nurse-bob");

    var v1 = repository.findHistoryVersion(id, 1);
    assertThat(v1).isPresent();
    assertThat(v1.get().versionId()).isEqualTo(1);
    assertThat(v1.get().changedBy()).isEqualTo("nurse-alice");
    assertThat(v1.get().operation()).isEqualTo("create");

    var v2 = repository.findHistoryVersion(id, 2);
    assertThat(v2).isPresent();
    assertThat(v2.get().versionId()).isEqualTo(2);
    assertThat(v2.get().changedBy()).isEqualTo("nurse-bob");
    assertThat(v2.get().operation()).isEqualTo("update");

    var v99 = repository.findHistoryVersion(id, 99);
    assertThat(v99).isEmpty();
  }

  @Test
  @DisplayName("Duplicate active schedule for same doctor and specialty is blocked by unique index")
  void duplicateActiveScheduleBlocked() {
    UUID doc = UUID.randomUUID();

    // First active schedule
    repository.create(
        new ScheduleRecord(
            UUID.randomUUID(),
            1,
            FIXED_NOW,
            true,
            doc,
            "cardiology",
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 31),
            "{}"),
        "nurse-alice");

    // Second active schedule for SAME doctor and specialty -> violates
    // ux_schedule_active_practitioner_specialty
    assertThatThrownBy(() ->
            repository.create(
                new ScheduleRecord(
                    UUID.randomUUID(),
                    1,
                    FIXED_NOW,
                    true,
                    doc,
                    "cardiology",
                    LocalDate.of(2026, 12, 1),
                    LocalDate.of(2026, 12, 31),
                    "{}"),
                "nurse-bob"))
        .isInstanceOf(DataIntegrityViolationException.class);

    // But an INACTIVE schedule for the same doctor and specialty is allowed
    var inactiveRecord =
        repository.create(
            new ScheduleRecord(
                UUID.randomUUID(),
                1,
                FIXED_NOW,
                false,
                doc,
                "cardiology",
                LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 31),
                "{}"),
            "nurse-bob");
    assertThat(inactiveRecord.active()).isFalse();
  }
}
