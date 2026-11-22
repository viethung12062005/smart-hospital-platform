package org.shp.scheduling.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.SlotGenerator;
import org.shp.scheduling.infra.FakePractitionerRoleClient;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.shp.web.auth.IdentityLink;
import org.shp.web.auth.IdentityLinkClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end integration tests for the slot generation API (S7, US-011): date limits, result
 * counts, idempotent reruns and authorization.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + SlotGenerationApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + SlotGenerationApiTest.TestSecurityConfig.AUDIENCE
    })
@Testcontainers
@Import(SlotGenerationApiTest.TestSecurityConfig.class)
class SlotGenerationApiTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  @Container @ServiceConnection
  static final RabbitMQContainer BUS =
      new RabbitMQContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_RABBITMQ_IMAGE", "rabbitmq:4-management"))
              .asCompatibleSubstituteFor("rabbitmq"));

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired Environment env;
  @Autowired TestSecurityConfig.FakeIdentityLinkClient identityLinks;
  @Autowired FakePractitionerRoleClient practitionerRoles;
  @Autowired JdbcClient jdbc;

  @TestConfiguration
  static class TestSecurityConfig {
    static final String ISSUER = "https://idp.test/realms/shp";
    static final String AUDIENCE = "shp-api";
    static final byte[] KEY =
        "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);

    @Bean
    @Primary
    JwtDecoder testJwtDecoder(ShpAuthProperties props, Clock clock) {
      var d =
          NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
              .macAlgorithm(MacAlgorithm.HS256)
              .build();
      d.setJwtValidator(ShpJwt.validator(props, clock));
      return d;
    }

    @Bean
    @Primary
    FakeIdentityLinkClient fakeIdentityLinkClient() {
      return new FakeIdentityLinkClient();
    }

    @Bean
    @Primary
    FakePractitionerRoleClient fakePractitionerRoleClient() {
      return new FakePractitionerRoleClient();
    }

    static class FakeIdentityLinkClient implements IdentityLinkClient {
      final Map<String, IdentityLink> links = new ConcurrentHashMap<>();

      @Override
      public Optional<IdentityLink> find(String subject) {
        return Optional.ofNullable(links.get(subject));
      }
    }
  }

  @BeforeEach
  void cleanup() {
    jdbc.sql(
            "TRUNCATE scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history CASCADE")
        .update();
    identityLinks.links.clear();
    practitionerRoles.clear();
  }

  private String token(String subject, List<String> roles) {
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(TestSecurityConfig.ISSUER)
              .subject(subject)
              .audience(TestSecurityConfig.AUDIENCE)
              .claim("roles", roles)
              .claim("amr", List.of("pwd", "mfa"))
              .issueTime(Date.from(now.minusSeconds(60)))
              .notBeforeTime(Date.from(now.minusSeconds(60)))
              .expirationTime(Date.from(now.plusSeconds(600)));
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
      jwt.sign(new MACSigner(TestSecurityConfig.KEY));
      return jwt.serialize();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private HttpResponse<String> send(HttpRequest req) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
  }

  private URI url(String path) {
    return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
  }

  private String createScheduleFor(UUID doctorId, String nurseSub) throws Exception {
    String requestBody =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "cardiology" }] }]
        }
        """
            .formatted(doctorId);

    var res =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(nurseSub, List.of("nurse")))
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build());
    assertThat(res.statusCode()).isEqualTo(201);
    return JSON.readTree(res.body()).path("id").asString().replace("Schedule/", "");
  }

  private HttpResponse<String> putTemplates(
      String scheduleId, String accessToken, String ifMatch, String jsonBody) throws Exception {
    var b =
        HttpRequest.newBuilder(url("/api/v1/scheduling/schedules/" + scheduleId + "/templates"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
    if (ifMatch != null) {
      b.header(HttpHeaders.IF_MATCH, ifMatch);
    }
    return send(b.PUT(HttpRequest.BodyPublishers.ofString(jsonBody)).build());
  }

  private HttpResponse<String> generate(String scheduleId, String accessToken, String jsonBody)
      throws Exception {
    return send(
        HttpRequest.newBuilder(
                url("/api/v1/scheduling/schedules/" + scheduleId + "/slots:generate"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build());
  }

  private static String range(LocalDate from, LocalDate to) {
    return "{\"from\": \"%s\", \"to\": \"%s\"}".formatted(from, to);
  }

  private static LocalDate today() {
    return LocalDate.now(SlotGenerator.VN_ZONE);
  }

  private String nurse() {
    String sub = "gen-nurse-" + UUID.randomUUID();
    identityLinks.links.put(
        sub, new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    return sub;
  }

  /** A schedule with a Monday-to-Sunday 08:00-11:30 / 15 min template (14 slots a day). */
  private String scheduleWithDailyTemplate(String nurseSub, String nurseToken) throws Exception {
    UUID docId = UUID.randomUUID();
    practitionerRoles.register(docId, "cardiology");
    String scheduleId = createScheduleFor(docId, nurseSub);
    StringBuilder templates = new StringBuilder();
    for (int weekday = 1; weekday <= 7; weekday++) {
      if (weekday > 1) {
        templates.append(',');
      }
      templates.append(
          "{\"weekday\": %d, \"startTime\": \"08:00\", \"endTime\": \"11:30\", \"slotMinutes\": 15, \"validFrom\": \"%s\", \"validTo\": null}"
              .formatted(weekday, today()));
    }
    var res =
        putTemplates(scheduleId, nurseToken, "W/\"1\"", "{\"templates\": [" + templates + "]}");
    assertThat(res.statusCode()).isEqualTo(200);
    return scheduleId;
  }

  @Test
  @DisplayName("S7: a 08:00-11:30 / 15 min template creates 14 slots a day; rerun skips them")
  void generatesFourteenSlotsPerDayAndRerunSkipsExisting() throws Exception {
    String sub = nurse();
    String token = token(sub, List.of("nurse"));
    String scheduleId = scheduleWithDailyTemplate(sub, token);
    LocalDate from = today().plusDays(1);
    LocalDate to = today().plusDays(2);

    var first = generate(scheduleId, token, range(from, to));
    assertThat(first.statusCode()).isEqualTo(200);
    JsonNode firstBody = JSON.readTree(first.body());
    assertThat(firstBody.path("created").asInt()).isEqualTo(28);
    assertThat(firstBody.path("skippedExisting").asInt()).isZero();

    var second = generate(scheduleId, token, range(from, to));
    assertThat(second.statusCode()).isEqualTo(200);
    JsonNode secondBody = JSON.readTree(second.body());
    assertThat(secondBody.path("created").asInt()).isZero();
    assertThat(secondBody.path("skippedExisting").asInt()).isEqualTo(28);

    int free =
        jdbc.sql(
                "SELECT count(*) FROM scheduling.slot WHERE schedule_id = ?::uuid AND status = 'free'")
            .param(scheduleId)
            .query(Integer.class)
            .single();
    assertThat(free).isEqualTo(28);
  }

  @Test
  @DisplayName("S7: dates outside the limits return 400 validation-failed and create nothing")
  void dateLimitsReturn400() throws Exception {
    String sub = nurse();
    String token = token(sub, List.of("nurse"));
    String scheduleId = scheduleWithDailyTemplate(sub, token);
    LocalDate today = today();

    // from in the past
    assertThat(generate(scheduleId, token, range(today.minusDays(1), today)).statusCode())
        .isEqualTo(400);
    // to beyond today + 30
    assertThat(generate(scheduleId, token, range(today, today.plusDays(31))).statusCode())
        .isEqualTo(400);
    // to before from
    assertThat(
            generate(scheduleId, token, range(today.plusDays(3), today.plusDays(2))).statusCode())
        .isEqualTo(400);
    // missing and malformed dates
    assertThat(generate(scheduleId, token, "{\"from\": \"" + today + "\"}").statusCode())
        .isEqualTo(400);
    assertThat(generate(scheduleId, token, "{\"from\": \"soon\", \"to\": \"later\"}").statusCode())
        .isEqualTo(400);
    var bad = generate(scheduleId, token, range(today.minusDays(1), today));
    assertThat(JSON.readTree(bad.body()).path("code").asString()).isEqualTo("validation-failed");
    int none = jdbc.sql("SELECT count(*) FROM scheduling.slot").query(Integer.class).single();
    assertThat(none).isZero();

    // boundaries are accepted: from = today, to = today + 30 (31 days inclusive)
    var ok = generate(scheduleId, token, range(today, today.plusDays(30)));
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(ok.body()).path("created").asInt()).isEqualTo(31 * 14);
  }

  @Test
  @DisplayName("S7: unknown schedule 404; inactive schedule 409; doctor and patient 403")
  void notFoundInactiveAndForbidden() throws Exception {
    String sub = nurse();
    String token = token(sub, List.of("nurse"));
    LocalDate from = today().plusDays(1);

    assertThat(generate(UUID.randomUUID().toString(), token, range(from, from)).statusCode())
        .isEqualTo(404);

    String scheduleId = scheduleWithDailyTemplate(sub, token);
    jdbc.sql("UPDATE scheduling.schedule SET active = false WHERE id = ?::uuid")
        .param(scheduleId)
        .update();
    assertThat(generate(scheduleId, token, range(from, from)).statusCode()).isEqualTo(409);

    String docSub = "gen-doc-" + UUID.randomUUID();
    identityLinks.links.put(
        docSub, new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    assertThat(
            generate(scheduleId, token(docSub, List.of("doctor")), range(from, from)).statusCode())
        .isEqualTo(403);
    String patSub = "gen-pat-" + UUID.randomUUID();
    identityLinks.links.put(
        patSub, new IdentityLink("Patient", UUID.randomUUID().toString(), false));
    assertThat(
            generate(scheduleId, token(patSub, List.of("patient")), range(from, from)).statusCode())
        .isEqualTo(403);
  }
}
