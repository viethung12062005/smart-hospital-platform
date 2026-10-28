package org.shp.data.postgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 16 thật (Testcontainers), dùng chung cho mọi lớp test. Đổi image bằng biến
 * SHP_TEST_POSTGRES_IMAGE (ví dụ mirror khi Docker Hub giới hạn lượt kéo).
 */
abstract class PostgresTestBase {
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));
  static final JdbcClient JDBC;
  static final TransactionTemplate TX;

  static {
    PG.start();
    var ds =
        new SimpleDriverDataSource(
            new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    JDBC = JdbcClient.create(ds);
    TX = new TransactionTemplate(new DataSourceTransactionManager(ds));
    try (var in =
        PostgresTestBase.class.getResourceAsStream(
            "/db/shp-messaging/postgres/V0_1__shp_messaging.sql")) {
      JDBC.sql(new String(in.readAllBytes(), StandardCharsets.UTF_8)).update();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    JDBC.sql("CREATE TABLE IF NOT EXISTS thing (id uuid PRIMARY KEY, name text NOT NULL)").update();
  }

  @BeforeEach
  void reset() {
    JDBC.sql("TRUNCATE outbox, inbox, idempotency_key, thing").update();
  }

  static long count(String sql) {
    return JDBC.sql(sql).query(Long.class).single();
  }

  static void insertThing(String name) {
    JDBC.sql("INSERT INTO thing VALUES (?, ?)").params(java.util.UUID.randomUUID(), name).update();
  }

  static final class MutableClock extends Clock {
    volatile Instant now;

    MutableClock(Instant start) {
      now = start;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
