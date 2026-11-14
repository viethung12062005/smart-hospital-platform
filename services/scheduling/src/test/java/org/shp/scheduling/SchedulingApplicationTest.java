package org.shp.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.shp.data.postgres.PostgresOutbox;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.web.authz.UnprotectedEndpoints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Framework test: the service starts with a real DB, platform endpoints are correct, and NO
 * endpoint is missing its authorization declaration.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
class SchedulingApplicationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  /** Local bus (13-events §1.1): the outbox relay publishes to a real RabbitMQ. */
  @Container @ServiceConnection
  static final RabbitMQContainer BUS =
      new RabbitMQContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_RABBITMQ_IMAGE", "rabbitmq:4-management"))
              .asCompatibleSubstituteFor("rabbitmq"));

  @Autowired Environment env;
  @Autowired PostgresOutbox outbox;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;
  @Autowired ApplicationContext ctx;

  private HttpResponse<String> get(String path) throws Exception {
    var uri = URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    try (var client = HttpClient.newHttpClient()) {
      return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  @Test
  void platformEndpointsWork() throws Exception {
    assertThat(get("/health/live").statusCode()).isEqualTo(200);
    assertThat(get("/health/ready").statusCode()).isEqualTo(200);
    assertThat(get("/info").body()).contains("\"name\":\"scheduling\"");
  }

  @Test
  void everyEndpointDeclaresItsAuthorizationRule() {
    assertThat(UnprotectedEndpoints.find(ctx)).isEmpty();
  }

  @Test
  void azurePasswordlessPluginIsPackaged() throws Exception {
    // uat/prod log in to PostgreSQL with this plugin (02-db-scheduling §5); without it there is no
    // DB
    assertThat(
            Class.forName(
                "com.azure.identity.extensions.jdbc.postgresql.AzurePostgresqlAuthenticationPlugin"))
        .isNotNull();
  }

  @Test
  void outboxEventsArePublishedToTheBus() {
    CloudEvent evt =
        CloudEvents.create(
            "scheduling",
            "shp.scheduling.appointment-booked.v1",
            "Appointment/" + UUID.randomUUID(),
            Map.of("appointmentId", UUID.randomUUID().toString()));
    tx.executeWithoutResult(s -> outbox.add(evt));

    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                jdbc.sql("SELECT published_at IS NOT NULL FROM outbox WHERE id = ?")
                    .param(UUID.fromString(evt.id()))
                    .query(Boolean.class)
                    .single());
  }

  @Test
  void apiRequiresAToken() throws Exception {
    var r = get("/api/v1/anything");
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.body()).contains("unauthenticated");
  }
}
