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
 * End-to-end integration tests for weekly schedule template APIs (S5–S6, US-010). Covers TC-AC-ST.1
 * (divisible time ranges), overlap checks, transactional replacement, If-Match/ETag CAS, and
 * authorization matrix enforcement.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + ScheduleTemplateApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + ScheduleTemplateApiTest.TestSecurityConfig.AUDIENCE
    })
@Testcontainers
@Import(ScheduleTemplateApiTest.TestSecurityConfig.class)
class ScheduleTemplateApiTest {

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

  private HttpResponse<String> getTemplates(String scheduleId, String accessToken)
      throws Exception {
    return send(
        HttpRequest.newBuilder(url("/api/v1/scheduling/schedules/" + scheduleId + "/templates"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .GET()
            .build());
  }

  @Test
  @DisplayName(
      "TC-AC-ST.1: 08:00-11:30 / 15 min saves cleanly; 08:00-11:20 / 15 min returns 400 validation-failed")
  void validTemplateSavesAndNonDivisibleRangeReturns400() throws Exception {
    UUID docId = UUID.randomUUID();
    practitionerRoles.register(docId, "cardiology");
    String nurseSub = "st-nurse-" + docId;
    identityLinks.links.put(
        nurseSub, new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));

    String scheduleId = createScheduleFor(docId, nurseSub);
    String nurseToken = token(nurseSub, List.of("nurse"));

    String validPayload =
        """
        {
          "templates": [
            { "weekday": 1, "startTime": "08:00", "endTime": "11:30", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null },
            { "weekday": 3, "startTime": "13:30", "endTime": "16:30", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }
          ]
        }
        """;

    var okRes = putTemplates(scheduleId, nurseToken, "W/\"1\"", validPayload);
    assertThat(okRes.statusCode()).isEqualTo(200);
    assertThat(okRes.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"2\"");

    JsonNode okBody = JSON.readTree(okRes.body());
    assertThat(okBody.path("affectedFutureSlots").asInt()).isZero();
    assertThat(okBody.path("templates")).hasSize(2);
    assertThat(okBody.path("templates").get(0).path("weekday").asInt()).isEqualTo(1);
    assertThat(okBody.path("templates").get(0).path("startTime").asString()).isEqualTo("08:00");
    assertThat(okBody.path("templates").get(0).path("endTime").asString()).isEqualTo("11:30");
    assertThat(okBody.path("templates").get(0).path("slotMinutes").asInt()).isEqualTo(15);
    assertThat(okBody.path("templates").get(0).path("validFrom").asString())
        .isEqualTo("2026-12-01");
    assertThat(okBody.path("templates").get(0).path("validTo").isNull()).isTrue();

    String nonDivisiblePayload =
        """
        {
          "templates": [
            { "weekday": 1, "startTime": "08:00", "endTime": "11:20", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }
          ]
        }
        """;

    var badRes = putTemplates(scheduleId, nurseToken, "W/\"2\"", nonDivisiblePayload);
    assertThat(badRes.statusCode()).isEqualTo(400);
    assertThat(badRes.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
        .contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(JSON.readTree(badRes.body()).path("code").asString()).isEqualTo("validation-failed");
  }

  @Test
  @DisplayName("S5: Replaces all templates in one transaction and bumps schedule ETag")
  void replaceAllTemplatesOverwritesExistingInSingleTransaction() throws Exception {
    UUID docId = UUID.randomUUID();
    practitionerRoles.register(docId, "cardiology");
    String nurseSub = "rep-nurse-" + docId;
    identityLinks.links.put(
        nurseSub, new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));

    String scheduleId = createScheduleFor(docId, nurseSub);
    String nurseToken = token(nurseSub, List.of("nurse"));

    var firstRes =
        putTemplates(
            scheduleId,
            nurseToken,
            "W/\"1\"",
            """
            {
              "templates": [
                { "weekday": 1, "startTime": "08:00", "endTime": "12:00", "slotMinutes": 30, "validFrom": "2026-12-01", "validTo": null },
                { "weekday": 2, "startTime": "08:00", "endTime": "12:00", "slotMinutes": 30, "validFrom": "2026-12-01", "validTo": null }
              ]
            }
            """);
    assertThat(firstRes.statusCode()).isEqualTo(200);
    assertThat(firstRes.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"2\"");

    var secondRes =
        putTemplates(
            scheduleId,
            nurseToken,
            "W/\"2\"",
            """
            {
              "templates": [
                { "weekday": 5, "startTime": "07:00", "endTime": "10:00", "slotMinutes": 20, "validFrom": "2026-12-05", "validTo": "2026-12-31" }
              ]
            }
            """);
    assertThat(secondRes.statusCode()).isEqualTo(200);
    assertThat(secondRes.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"3\"");

    var getRes = getTemplates(scheduleId, nurseToken);
    assertThat(getRes.statusCode()).isEqualTo(200);
    assertThat(getRes.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"3\"");

    JsonNode templates = JSON.readTree(getRes.body()).path("templates");
    assertThat(templates).hasSize(1);
    assertThat(templates.get(0).path("weekday").asInt()).isEqualTo(5);
    assertThat(templates.get(0).path("validTo").asString()).isEqualTo("2026-12-31");
  }

  @Test
  @DisplayName("S5: Validation failures return 400 without altering existing templates")
  void s5ValidationFailuresReturn400WithoutModifyingExistingTemplates() throws Exception {
    UUID docId = UUID.randomUUID();
    practitionerRoles.register(docId, "cardiology");
    String nurseSub = "val-nurse-" + docId;
    identityLinks.links.put(
        nurseSub, new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));

    String scheduleId = createScheduleFor(docId, nurseSub);
    String nurseToken = token(nurseSub, List.of("nurse"));

    var seedRes =
        putTemplates(
            scheduleId,
            nurseToken,
            "W/\"1\"",
            """
            {
              "templates": [
                { "weekday": 2, "startTime": "08:00", "endTime": "12:00", "slotMinutes": 20, "validFrom": "2026-12-01", "validTo": null }
              ]
            }
            """);
    assertThat(seedRes.statusCode()).isEqualTo(200);

    List<String> invalidBodies =
        List.of(
            // startTime > endTime
            """
            { "templates": [{ "weekday": 1, "startTime": "11:30", "endTime": "08:00", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }] }
            """,
            // startTime == endTime
            """
            { "templates": [{ "weekday": 1, "startTime": "08:00", "endTime": "08:00", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }] }
            """,
            // slotMinutes outside {10, 15, 20, 30}
            """
            { "templates": [{ "weekday": 1, "startTime": "08:00", "endTime": "10:30", "slotMinutes": 25, "validFrom": "2026-12-01", "validTo": null }] }
            """,
            // not divisible by slotMinutes
            """
            { "templates": [{ "weekday": 1, "startTime": "08:00", "endTime": "11:20", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }] }
            """,
            // two overlapping templates on same weekday
            """
            {
              "templates": [
                { "weekday": 1, "startTime": "08:00", "endTime": "11:30", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null },
                { "weekday": 1, "startTime": "11:00", "endTime": "13:00", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }
              ]
            }
            """,
            // weekday out of range (0 and 8)
            """
            { "templates": [{ "weekday": 0, "startTime": "08:00", "endTime": "10:00", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }] }
            """,
            """
            { "templates": [{ "weekday": 8, "startTime": "08:00", "endTime": "10:00", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }] }
            """,
            // validTo < validFrom
            """
            { "templates": [{ "weekday": 1, "startTime": "08:00", "endTime": "10:00", "slotMinutes": 15, "validFrom": "2026-12-10", "validTo": "2026-12-01" }] }
            """,
            // missing templates array
            "{}");

    for (String payload : invalidBodies) {
      var res = putTemplates(scheduleId, nurseToken, "W/\"2\"", payload);
      assertThat(res.statusCode()).as("Expected 400 for payload: %s", payload).isEqualTo(400);
      assertThat(JSON.readTree(res.body()).path("code").asString()).isEqualTo("validation-failed");
    }

    // Existing template and version must remain untouched
    var getRes = getTemplates(scheduleId, nurseToken);
    assertThat(getRes.statusCode()).isEqualTo(200);
    assertThat(getRes.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"2\"");
    JsonNode existing = JSON.readTree(getRes.body()).path("templates");
    assertThat(existing).hasSize(1);
    assertThat(existing.get(0).path("weekday").asInt()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "S5: malformed or missing fields return 400; adjacent and cross-weekday ranges are valid")
  void s5MalformedFieldsReturn400AndNonOverlappingRangesAreAccepted() throws Exception {
    UUID docId = UUID.randomUUID();
    practitionerRoles.register(docId, "cardiology");
    String nurseSub = "mal-nurse-" + docId;
    identityLinks.links.put(
        nurseSub, new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));

    String scheduleId = createScheduleFor(docId, nurseSub);
    String nurseToken = token(nurseSub, List.of("nurse"));

    List<String> invalidBodies =
        List.of(
            // unparseable start time
            """
            { "templates": [{ "weekday": 1, "startTime": "8am", "endTime": "10:00", "slotMinutes": 15, "validFrom": "2026-12-01" }] }
            """,
            // time with seconds is not minute-aligned
            """
            { "templates": [{ "weekday": 1, "startTime": "08:00:30", "endTime": "10:00", "slotMinutes": 15, "validFrom": "2026-12-01" }] }
            """,
            // unparseable validFrom
            """
            { "templates": [{ "weekday": 1, "startTime": "08:00", "endTime": "10:00", "slotMinutes": 15, "validFrom": "01/12/2026" }] }
            """,
            // unparseable validTo
            """
            { "templates": [{ "weekday": 1, "startTime": "08:00", "endTime": "10:00", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": "never" }] }
            """,
            // missing required fields
            """
            { "templates": [{ "weekday": 1, "endTime": "10:00", "slotMinutes": 15, "validFrom": "2026-12-01" }] }
            """,
            """
            { "templates": [{ "weekday": 1, "startTime": "08:00", "endTime": "10:00", "validFrom": "2026-12-01" }] }
            """,
            """
            { "templates": [{ "weekday": 1, "startTime": "08:00", "endTime": "10:00", "slotMinutes": 15 }] }
            """,
            // null templates array
            """
            { "templates": null }
            """);
    for (String payload : invalidBodies) {
      var res = putTemplates(scheduleId, nurseToken, "W/\"1\"", payload);
      assertThat(res.statusCode()).as("Expected 400 for payload: %s", payload).isEqualTo(400);
      assertThat(JSON.readTree(res.body()).path("code").asString()).isEqualTo("validation-failed");
    }

    // Back-to-back ranges on one weekday and the same range on another weekday do not overlap
    var okRes =
        putTemplates(
            scheduleId,
            nurseToken,
            "W/\"1\"",
            """
            {
              "templates": [
                { "weekday": 1, "startTime": "08:00", "endTime": "10:00", "slotMinutes": 20, "validFrom": "2026-12-01", "validTo": "2026-12-31" },
                { "weekday": 1, "startTime": "10:00", "endTime": "12:00", "slotMinutes": 20, "validFrom": "2026-12-01", "validTo": "2026-12-31" },
                { "weekday": 2, "startTime": "08:00", "endTime": "10:00", "slotMinutes": 20, "validFrom": "2026-12-01", "validTo": "2026-12-01" }
              ]
            }
            """);
    assertThat(okRes.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(okRes.body()).path("templates")).hasSize(3);
  }

  @Test
  @DisplayName(
      "S5 & S6: Missing If-Match returns 428, stale If-Match returns 412, missing schedule returns 404")
  void s5PreconditionConflictAndNotFoundCases() throws Exception {
    UUID docId = UUID.randomUUID();
    practitionerRoles.register(docId, "cardiology");
    String nurseSub = "prec-nurse-" + docId;
    identityLinks.links.put(
        nurseSub, new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));

    String scheduleId = createScheduleFor(docId, nurseSub);
    String nurseToken = token(nurseSub, List.of("nurse"));

    String validPayload =
        """
        { "templates": [{ "weekday": 1, "startTime": "08:00", "endTime": "11:00", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }] }
        """;

    // Missing If-Match -> 428 Precondition Required
    var missingRes = putTemplates(scheduleId, nurseToken, null, validPayload);
    assertThat(missingRes.statusCode()).isEqualTo(428);

    // Stale If-Match -> 412 Precondition Failed
    var staleRes = putTemplates(scheduleId, nurseToken, "W/\"99\"", validPayload);
    assertThat(staleRes.statusCode()).isEqualTo(412);

    // Non-existent schedule -> 404 Not Found
    String missingScheduleId = UUID.randomUUID().toString();
    assertThat(putTemplates(missingScheduleId, nurseToken, "W/\"1\"", validPayload).statusCode())
        .isEqualTo(404);
    assertThat(getTemplates(missingScheduleId, nurseToken).statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName(
      "S5 & S6: Nurse/Admin can edit and view; Doctor views only own templates (403 on edit or others); Patient 403")
  void s5AndS6AuthorizationPermissions() throws Exception {
    UUID docA = UUID.randomUUID();
    UUID docB = UUID.randomUUID();
    practitionerRoles.register(docA, "cardiology");
    practitionerRoles.register(docB, "pediatrics");

    String nurseSub = "auth-nurse-" + docA;
    String adminSub = "auth-admin-" + docA;
    String docASub = "auth-docA-" + docA;
    String docBSub = "auth-docB-" + docB;
    String patSub = "auth-pat-" + docA;

    identityLinks.links.put(
        nurseSub, new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    identityLinks.links.put(
        adminSub, new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    identityLinks.links.put(docASub, new IdentityLink("Practitioner", docA.toString(), false));
    identityLinks.links.put(docBSub, new IdentityLink("Practitioner", docB.toString(), false));
    identityLinks.links.put(
        patSub, new IdentityLink("Patient", UUID.randomUUID().toString(), false));

    String scheduleId = createScheduleFor(docA, nurseSub);
    String adminToken = token(adminSub, List.of("admin"));
    String docAToken = token(docASub, List.of("doctor"));
    String docBToken = token(docBSub, List.of("doctor"));
    String patToken = token(patSub, List.of("patient"));

    String validPayload =
        """
        { "templates": [{ "weekday": 4, "startTime": "08:00", "endTime": "11:00", "slotMinutes": 30, "validFrom": "2026-12-01", "validTo": null }] }
        """;

    // Admin PUT (S5) and GET (S6) -> 200
    var adminPut = putTemplates(scheduleId, adminToken, "W/\"1\"", validPayload);
    assertThat(adminPut.statusCode()).isEqualTo(200);
    assertThat(adminPut.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"2\"");
    assertThat(getTemplates(scheduleId, adminToken).statusCode()).isEqualTo(200);

    // Doctor A GET own templates (S6) -> 200
    var docAGet = getTemplates(scheduleId, docAToken);
    assertThat(docAGet.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(docAGet.body()).path("templates")).hasSize(1);

    // Doctor B GET Doc A templates (S6) -> 403
    assertThat(getTemplates(scheduleId, docBToken).statusCode()).isEqualTo(403);

    // Doctor A PUT own templates (S5) -> 403
    assertThat(putTemplates(scheduleId, docAToken, "W/\"2\"", validPayload).statusCode())
        .isEqualTo(403);

    // Patient GET and PUT -> 403
    assertThat(getTemplates(scheduleId, patToken).statusCode()).isEqualTo(403);
    assertThat(putTemplates(scheduleId, patToken, "W/\"2\"", validPayload).statusCode())
        .isEqualTo(403);
  }
}
