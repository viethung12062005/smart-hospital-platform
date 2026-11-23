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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.infra.FakePractitionerRoleClient;
import org.shp.scheduling.infra.ScheduleRepository;
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
 * End-to-end integration tests for Schedule API (S1–S4): Covers: - 201/400/409/403/404/412/428
 * status codes - Authorization matrix verification (Doctor sees only own schedule) - TC-AC-02.3:
 * doctor viewing another doctor's schedule or editing schedule is rejected
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + ScheduleApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + ScheduleApiTest.TestSecurityConfig.AUDIENCE,
      "shp.paging.token-key=test-only-page-token-key-32-bytes-minimum!!"
    })
@Testcontainers
@Import(ScheduleApiTest.TestSecurityConfig.class)
class ScheduleApiTest {

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
  @Autowired ScheduleRepository scheduleRepository;
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
              .claim("amr", List.of("pwd", "mfa")) // staff MFA satisfied
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

  @Test
  @DisplayName(
      "S1: Nurse can create schedule (201 Created) with Location, ETag, and auto planningHorizon")
  void nurseCanCreateSchedule() throws Exception {
    UUID doctorId = UUID.randomUUID();
    practitionerRoles.register(doctorId, "cardiology");
    identityLinks.links.put(
        "nurse-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));

    String requestBody =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s", "display": "Dr. Le Minh Quan" }],
          "specialty": [{ "coding": [{ "system": "https://shp.example.org/fhir/CodeSystem/specialty", "code": "cardiology" }] }],
          "comment": "Room 204"
        }
        """
            .formatted(doctorId);

    var req =
        HttpRequest.newBuilder(url("/fhir/Schedule"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("nurse-1", List.of("nurse")))
            .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    var res = send(req);
    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(res.headers().firstValue(HttpHeaders.LOCATION)).isPresent();
    assertThat(res.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"1\"");

    JsonNode body = JSON.readTree(res.body());
    assertThat(body.path("resourceType").asString()).isEqualTo("Schedule");
    assertThat(body.path("id").asString()).isNotBlank();
    assertThat(body.path("meta").path("versionId").asString()).isEqualTo("1");
    assertThat(body.path("planningHorizon").path("start").asString()).isNotBlank();
    assertThat(body.path("planningHorizon").path("end").asString()).isNotBlank();
    assertThat(body.path("comment").asString()).isEqualTo("Room 204");
  }

  @Test
  @DisplayName("S1: Admin can create schedule (201 Created)")
  void adminCanCreateSchedule() throws Exception {
    UUID doctorId = UUID.randomUUID();
    practitionerRoles.register(doctorId, "pediatrics");
    identityLinks.links.put(
        "admin-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));

    String requestBody =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "pediatrics" }] }]
        }
        """
            .formatted(doctorId);

    var req =
        HttpRequest.newBuilder(url("/fhir/Schedule"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("admin-1", List.of("admin")))
            .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    var res = send(req);
    assertThat(res.statusCode()).isEqualTo(201);
  }

  @Test
  @DisplayName("S1: Doctor and Patient are forbidden from creating schedule (403)")
  void doctorAndPatientCannotCreateSchedule() throws Exception {
    UUID doctorId = UUID.randomUUID();
    practitionerRoles.register(doctorId, "cardiology");
    identityLinks.links.put("doc-1", new IdentityLink("Practitioner", doctorId.toString(), false));
    identityLinks.links.put(
        "patient-1", new IdentityLink("Patient", UUID.randomUUID().toString(), false));

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

    // Doctor attempt
    var docReq =
        HttpRequest.newBuilder(url("/fhir/Schedule"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("doc-1", List.of("doctor")))
            .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    assertThat(send(docReq).statusCode()).isEqualTo(403);

    // Patient attempt
    var patReq =
        HttpRequest.newBuilder(url("/fhir/Schedule"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("patient-1", List.of("patient")))
            .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    assertThat(send(patReq).statusCode()).isEqualTo(403);
  }

  @Test
  @DisplayName(
      "S1: 400 validation failures (invalid actor, unknown specialty, missing doctor role)")
  void s1ValidationFailuresReturn400() throws Exception {
    identityLinks.links.put(
        "nurse-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    String nurseToken = token("nurse-1", List.of("nurse"));

    UUID docA = UUID.randomUUID();
    practitionerRoles.register(docA, "cardiology");

    // Case 1: Multiple actors
    String multiActor =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }, { "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "cardiology" }] }]
        }
        """
            .formatted(docA, UUID.randomUUID());
    var r1 =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(multiActor))
                .build());
    assertThat(r1.statusCode()).isEqualTo(400);

    // Case 2: Specialty outside value set
    String badSpecialty =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "unknown-specialty" }] }]
        }
        """
            .formatted(docA);
    var r2 =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(badSpecialty))
                .build());
    assertThat(r2.statusCode()).isEqualTo(400);

    // Case 3: Doctor does not have role in that specialty (docA has cardiology, not dermatology)
    String wrongRole =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "dermatology" }] }]
        }
        """
            .formatted(docA);
    var r3 =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(wrongRole))
                .build());
    assertThat(r3.statusCode()).isEqualTo(400);

    // Case 4: Unregistered / non-existent practitioner
    String nonExistentDoc =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "cardiology" }] }]
        }
        """
            .formatted(UUID.randomUUID());
    var r4 =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(nonExistentDoc))
                .build());
    assertThat(r4.statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("S1: Duplicate active schedule for same doctor and specialty returns 409")
  void duplicateActiveScheduleReturns409() throws Exception {
    identityLinks.links.put(
        "nurse-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    String nurseToken = token("nurse-1", List.of("nurse"));

    UUID docA = UUID.randomUUID();
    practitionerRoles.register(docA, "cardiology");

    String body =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "cardiology" }] }]
        }
        """
            .formatted(docA);

    // First active schedule succeeds
    var r1 =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    assertThat(r1.statusCode()).isEqualTo(201);

    // Second active schedule for same doctor and specialty fails with 409
    var r2 =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    assertThat(r2.statusCode()).isEqualTo(409);
  }

  @Test
  @DisplayName(
      "S2: Read schedule - Nurse/Admin can read, Doctor can only read own schedule (TC-AC-02.3)")
  void s2ReadSchedulePermissions() throws Exception {
    UUID docA = UUID.randomUUID();
    UUID docB = UUID.randomUUID();
    practitionerRoles.register(docA, "cardiology");
    practitionerRoles.register(docB, "pediatrics");

    // Use unique subjects per test to avoid IdentityResolver 60-second cache collisions
    String subDocA = "s2-doc-" + docA;
    String subDocB = "s2-doc-" + docB;

    identityLinks.links.put(
        "nurse-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    identityLinks.links.put(
        "admin-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    identityLinks.links.put(subDocA, new IdentityLink("Practitioner", docA.toString(), false));
    identityLinks.links.put(subDocB, new IdentityLink("Practitioner", docB.toString(), false));

    // Nurse creates Schedule for Doc A
    String bodyA =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "cardiology" }] }]
        }
        """
            .formatted(docA);

    var createRes =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("nurse-1", List.of("nurse")))
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(bodyA))
                .build());
    assertThat(createRes.statusCode()).isEqualTo(201);
    String scheduleId =
        JSON.readTree(createRes.body()).path("id").asString().replace("Schedule/", "");

    // 1. Nurse reads Doc A's schedule -> 200
    var rNurse =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule/" + scheduleId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("nurse-1", List.of("nurse")))
                .GET()
                .build());
    assertThat(rNurse.statusCode()).isEqualTo(200);
    assertThat(rNurse.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"1\"");

    // 2. Admin reads Doc A's schedule -> 200
    var rAdmin =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule/" + scheduleId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("admin-1", List.of("admin")))
                .GET()
                .build());
    assertThat(rAdmin.statusCode()).isEqualTo(200);

    // 3. Doc A reads OWN schedule -> 200
    var rDocA =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule/" + scheduleId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(subDocA, List.of("doctor")))
                .GET()
                .build());
    assertThat(rDocA.statusCode()).isEqualTo(200);

    // 4. TC-AC-02.3: Doc B reads Doc A's schedule -> 403 Forbidden
    doctorViewingAnotherDoctorScheduleIsRejected(scheduleId, subDocB);

    // 5. Non-existent schedule -> 404
    var rNotFound =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule/" + UUID.randomUUID()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("nurse-1", List.of("nurse")))
                .GET()
                .build());
    assertThat(rNotFound.statusCode()).isEqualTo(404);
  }

  void doctorViewingAnotherDoctorScheduleIsRejected(String scheduleId, String docBSubject)
      throws Exception {
    var rDocB =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule/" + scheduleId))
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + token(docBSubject, List.of("doctor")))
                .GET()
                .build());
    assertThat(rDocB.statusCode()).isEqualTo(403);
  }

  @Test
  @DisplayName(
      "S3: Find schedules - Doctor without actor sees only own schedules, with other actor gets 403")
  void s3FindSchedulesWithRoleRestrictions() throws Exception {
    UUID docA = UUID.randomUUID();
    UUID docB = UUID.randomUUID();
    practitionerRoles.register(docA, "cardiology");
    practitionerRoles.register(docB, "cardiology");

    // Use unique subjects per test to avoid IdentityResolver cache collisions
    String subDocA = "s3-doc-" + docA;
    String subDocB = "s3-doc-" + docB;

    identityLinks.links.put(
        "nurse-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    identityLinks.links.put(subDocA, new IdentityLink("Practitioner", docA.toString(), false));
    identityLinks.links.put(subDocB, new IdentityLink("Practitioner", docB.toString(), false));

    String nurseToken = token("nurse-1", List.of("nurse"));

    // Create 1 schedule for Doc A and 1 for Doc B
    send(
        HttpRequest.newBuilder(url("/fhir/Schedule"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
            .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    { "resourceType": "Schedule", "active": true, "actor": [{"reference":"Practitioner/%s"}], "specialty": [{"coding":[{"code":"cardiology"}]}] }
                    """
                        .formatted(docA)))
            .build());

    send(
        HttpRequest.newBuilder(url("/fhir/Schedule"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
            .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    { "resourceType": "Schedule", "active": true, "actor": [{"reference":"Practitioner/%s"}], "specialty": [{"coding":[{"code":"cardiology"}]}] }
                    """
                        .formatted(docB)))
            .build());

    // Nurse searches -> sees both (2 entries, no total per 07)
    var rNurse =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .GET()
                .build());
    assertThat(rNurse.statusCode()).isEqualTo(200);
    JsonNode nurseBundle = JSON.readTree(rNurse.body());
    assertThat(nurseBundle.path("resourceType").asString()).isEqualTo("Bundle");
    assertThat(nurseBundle.has("total")).isFalse();
    assertThat(nurseBundle.path("entry")).hasSize(2);

    // Doctor A searches WITHOUT actor param -> server automatically sets actor=<self>, returns ONLY
    // 1
    var rDocA =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(subDocA, List.of("doctor")))
                .GET()
                .build());
    assertThat(rDocA.statusCode()).isEqualTo(200);
    JsonNode docABundle = JSON.readTree(rDocA.body());
    assertThat(docABundle.path("entry")).hasSize(1);
    assertThat(
            docABundle
                .path("entry")
                .get(0)
                .path("resource")
                .path("actor")
                .get(0)
                .path("reference")
                .asString())
        .isEqualTo("Practitioner/" + docA);

    // Doctor A searches with OWN actor param -> 200 OK
    var rDocAOwn =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule?actor=Practitioner/" + docA))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(subDocA, List.of("doctor")))
                .GET()
                .build());
    assertThat(rDocAOwn.statusCode()).isEqualTo(200);

    // Doctor A searches with Doc B's actor param -> 403 Forbidden
    var rDocAOther =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule?actor=Practitioner/" + docB))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(subDocA, List.of("doctor")))
                .GET()
                .build());
    assertThat(rDocAOther.statusCode()).isEqualTo(403);
  }

  @Test
  @DisplayName(
      "S4: PUT /fhir/Schedule/{id} - Nurse updates schedule with If-Match, Doctor is forbidden (TC-AC-02.3)")
  void s4UpdateSchedule() throws Exception {
    UUID docA = UUID.randomUUID();
    practitionerRoles.register(docA, "cardiology");

    // Use unique subjects per test to avoid IdentityResolver cache collisions
    String subDocA = "s4-doc-" + docA;

    identityLinks.links.put(
        "nurse-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    identityLinks.links.put(subDocA, new IdentityLink("Practitioner", docA.toString(), false));

    String nurseToken = token("nurse-1", List.of("nurse"));

    // Create schedule
    var createRes =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        { "resourceType": "Schedule", "active": true, "actor": [{"reference":"Practitioner/%s"}], "specialty": [{"coding":[{"code":"cardiology"}]}] }
                        """
                            .formatted(docA)))
                .build());
    assertThat(createRes.statusCode()).isEqualTo(201);
    String scheduleId =
        JSON.readTree(createRes.body()).path("id").asString().replace("Schedule/", "");

    // 1. Update without If-Match -> 428 Precondition Required
    var rMissingIfMatch =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule/" + scheduleId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        { "resourceType": "Schedule", "active": false, "actor": [{"reference":"Practitioner/%s"}], "specialty": [{"coding":[{"code":"cardiology"}]}] }
                        """
                            .formatted(docA)))
                .build());
    assertThat(rMissingIfMatch.statusCode()).isEqualTo(428);

    // 2. Update with stale If-Match (W/"99") -> 412 Precondition Failed
    var rStaleIfMatch =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule/" + scheduleId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .header(HttpHeaders.IF_MATCH, "W/\"99\"")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        { "resourceType": "Schedule", "active": false, "actor": [{"reference":"Practitioner/%s"}], "specialty": [{"coding":[{"code":"cardiology"}]}] }
                        """
                            .formatted(docA)))
                .build());
    assertThat(rStaleIfMatch.statusCode()).isEqualTo(412);

    // 3. TC-AC-02.3: Doctor tries to edit schedule -> 403 Forbidden
    doctorEditingScheduleIsRejected(scheduleId, docA, subDocA);

    // 4. Nurse updates with valid If-Match: W/"1" -> 200 OK, ETag: W/"2"
    var rSuccess =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule/" + scheduleId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .header(HttpHeaders.IF_MATCH, "W/\"1\"")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        { "resourceType": "Schedule", "active": false, "actor": [{"reference":"Practitioner/%s"}], "specialty": [{"coding":[{"code":"cardiology"}]}], "comment": "Doctor on leave" }
                        """
                            .formatted(docA)))
                .build());
    assertThat(rSuccess.statusCode()).isEqualTo(200);
    assertThat(rSuccess.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"2\"");

    JsonNode updatedBody = JSON.readTree(rSuccess.body());
    assertThat(updatedBody.path("active").asBoolean()).isFalse();
    assertThat(updatedBody.path("comment").asString()).isEqualTo("Doctor on leave");
    assertThat(updatedBody.path("meta").path("versionId").asString()).isEqualTo("2");
  }

  void doctorEditingScheduleIsRejected(String scheduleId, UUID docA, String docASubject)
      throws Exception {
    var rDocUpdate =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule/" + scheduleId))
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + token(docASubject, List.of("doctor")))
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .header(HttpHeaders.IF_MATCH, "W/\"1\"")
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        { "resourceType": "Schedule", "active": false, "actor": [{"reference":"Practitioner/%s"}], "specialty": [{"coding":[{"code":"cardiology"}]}] }
                        """
                            .formatted(docA)))
                .build());
    assertThat(rDocUpdate.statusCode()).isEqualTo(403);
  }

  @Test
  @DisplayName(
      "S2-S4 edge cases: bad ids, unknown schedule, immutable actor/specialty, _count bounds")
  void edgeCasesReturnCatalogErrors() throws Exception {
    UUID docA = UUID.randomUUID();
    UUID docB = UUID.randomUUID();
    practitionerRoles.register(docA, "cardiology");
    practitionerRoles.register(docB, "cardiology");
    String nurseToken = token("nurse-edge", List.of("nurse"));
    identityLinks.links.put(
        "nurse-edge", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));

    var created = createSchedule(nurseToken, docA);
    assertThat(created.statusCode()).isEqualTo(201);
    String id = JSON.readTree(created.body()).path("id").asString().replace("Schedule/", "");

    // Malformed id -> 404; unknown id on PUT -> 404
    assertThat(get("/fhir/Schedule/not-a-uuid", nurseToken)).isEqualTo(404);
    assertThat(
            put(
                "/fhir/Schedule/" + UUID.randomUUID(),
                nurseToken,
                "W/\"1\"",
                scheduleJson(true, docA, "cardiology")))
        .isEqualTo(404);

    // Changing the doctor or the specialty, or omitting active, is rejected (400)
    assertThat(
            put(
                "/fhir/Schedule/" + id,
                nurseToken,
                "W/\"1\"",
                scheduleJson(true, docB, "cardiology")))
        .isEqualTo(400);
    assertThat(
            put(
                "/fhir/Schedule/" + id,
                nurseToken,
                "W/\"1\"",
                scheduleJson(true, docA, "neurology")))
        .isEqualTo(400);
    assertThat(
            put(
                "/fhir/Schedule/" + id,
                nurseToken,
                "W/\"1\"",
                "{\"resourceType\":\"Schedule\",\"actor\":[{\"reference\":\"Practitioner/"
                    + docA
                    + "\"}],\"specialty\":[{\"coding\":[{\"code\":\"cardiology\"}]}]}"))
        .isEqualTo(400);
    assertThat(
            put(
                "/fhir/Schedule/" + id,
                nurseToken,
                "W/\"1\"",
                "{\"resourceType\":\"Schedule\",\"active\":true,\"specialty\":[{\"coding\":[{\"code\":\"cardiology\"}]}]}"))
        .isEqualTo(400);

    // Body id that differs from the path id -> 400
    assertThat(
            put(
                "/fhir/Schedule/" + id,
                nurseToken,
                "W/\"1\"",
                scheduleJson(true, docA, "cardiology")
                    .replace("{", "{\"id\":\"" + UUID.randomUUID() + "\",")))
        .isEqualTo(400);

    // _count outside 1..100 and a malformed actor -> 400
    assertThat(get("/fhir/Schedule?_count=0", nurseToken)).isEqualTo(400);
    assertThat(get("/fhir/Schedule?_count=101", nurseToken)).isEqualTo(400);
    assertThat(get("/fhir/Schedule?_count=100", nurseToken)).isEqualTo(200);
    assertThat(get("/fhir/Schedule?actor=Practitioner/not-a-uuid", nurseToken)).isEqualTo(400);

    // Unauthenticated -> 401
    assertThat(get("/fhir/Schedule", null)).isEqualTo(401);

    // A doctor with no linked identity at all cannot search -> 403
    assertThat(get("/fhir/Schedule", token("doc-unlinked", List.of("doctor")))).isEqualTo(403);
  }

  @Test
  @DisplayName("S3: keyset paging - _page_token and Bundle.link[next] walk every schedule once")
  void s3KeysetPagingWalksAllSchedulesOnce() throws Exception {
    String nurse = token("nurse-page", List.of("nurse"));
    identityLinks.links.put(
        "nurse-page", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    Set<String> created = new HashSet<>();
    for (int i = 0; i < 5; i++) {
      UUID doc = UUID.randomUUID();
      practitionerRoles.register(doc, "cardiology");
      var res = createSchedule(nurse, doc);
      assertThat(res.statusCode()).isEqualTo(201);
      created.add(JSON.readTree(res.body()).path("id").asString().replace("Schedule/", ""));
    }

    List<String> seen = new ArrayList<>();
    String path = "/fhir/Schedule?specialty=cardiology&_count=2";
    int pages = 0;
    while (path != null) {
      var res = getResponse(path, nurse);
      assertThat(res.statusCode()).isEqualTo(200);
      JsonNode bundle = JSON.readTree(res.body());
      assertThat(bundle.path("type").asString()).isEqualTo("searchset");
      assertThat(bundle.has("total")).isFalse();
      assertThat(link(bundle, "self")).contains("specialty=cardiology").contains("_count=2");
      assertThat(bundle.path("entry").size()).isLessThanOrEqualTo(2);
      for (JsonNode entry : bundle.path("entry")) {
        seen.add(entry.path("resource").path("id").asString().replace("Schedule/", ""));
        assertThat(entry.path("fullUrl").asString()).endsWith("/fhir/Schedule/" + seen.getLast());
      }
      String next = link(bundle, "next");
      assertThat(next == null).isEqualTo(bundle.path("entry").size() < 2 || pages == 2);
      if (next != null) {
        assertThat(next).contains("_page_token=").doesNotContain("_offset");
        path = next.substring(next.indexOf("/fhir/Schedule"));
      } else {
        path = null;
      }
      pages++;
    }
    assertThat(pages).isEqualTo(3);
    assertThat(seen)
        .hasSize(5)
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrderElementsOf(created);
    assertThat(seen).isSorted();
  }

  @Test
  @DisplayName("S3: a page exactly full on the last page has no next link")
  void s3ExactlyFullLastPageHasNoNextLink() throws Exception {
    String nurse = token("nurse-exact", List.of("nurse"));
    identityLinks.links.put(
        "nurse-exact", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    for (int i = 0; i < 2; i++) {
      UUID doc = UUID.randomUUID();
      practitionerRoles.register(doc, "cardiology");
      assertThat(createSchedule(nurse, doc).statusCode()).isEqualTo(201);
    }
    JsonNode bundle = JSON.readTree(getResponse("/fhir/Schedule?_count=2", nurse).body());
    assertThat(bundle.path("entry")).hasSize(2);
    assertThat(link(bundle, "next")).isNull();
    assertThat(link(bundle, "self")).isNotNull();
  }

  @Test
  @DisplayName("S3: tampered, foreign, malformed and mismatched _page_token values are 400")
  void s3InvalidPageTokensAreRejected() throws Exception {
    String nurse = token("nurse-tok", List.of("nurse"));
    identityLinks.links.put(
        "nurse-tok", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    for (int i = 0; i < 3; i++) {
      UUID doc = UUID.randomUUID();
      practitionerRoles.register(doc, "cardiology");
      assertThat(createSchedule(nurse, doc).statusCode()).isEqualTo(201);
    }
    JsonNode first =
        JSON.readTree(getResponse("/fhir/Schedule?specialty=cardiology&_count=1", nurse).body());
    String next = link(first, "next");
    String tokenValue =
        java.net.URLDecoder.decode(
            next.substring(next.indexOf("_page_token=") + "_page_token=".length()),
            StandardCharsets.UTF_8);

    // The untouched token works
    assertThat(
            get(
                "/fhir/Schedule?specialty=cardiology&_count=1&_page_token="
                    + java.net.URLEncoder.encode(tokenValue, StandardCharsets.UTF_8),
                nurse))
        .isEqualTo(200);

    // Tampered payload (signature no longer matches)
    int dot = tokenValue.indexOf('.');
    String tamperedPayload =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    new String(
                            Base64.getUrlDecoder().decode(tokenValue.substring(0, dot)),
                            StandardCharsets.UTF_8)
                        .replace("\"id\"", "\"ID\"")
                        .getBytes(StandardCharsets.UTF_8))
            + tokenValue.substring(dot);
    assertThat(
            getResponse("/fhir/Schedule?specialty=cardiology&_page_token=" + tamperedPayload, nurse)
                .statusCode())
        .isEqualTo(400);

    // Tampered signature: flip a bit in a real signature byte. Replacing the last base64url
    // character is not enough, because its low bits are padding the decoder ignores.
    byte[] signature = Base64.getUrlDecoder().decode(tokenValue.substring(dot + 1));
    signature[0] ^= 0x01;
    String tamperedSig =
        tokenValue.substring(0, dot + 1)
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    assertThat(tamperedSig).isNotEqualTo(tokenValue);
    assertThat(get("/fhir/Schedule?specialty=cardiology&_page_token=" + tamperedSig, nurse))
        .isEqualTo(400);

    // Token issued for another filter set
    assertThat(get("/fhir/Schedule?specialty=neurology&_page_token=" + tokenValue, nurse))
        .isEqualTo(400);
    assertThat(get("/fhir/Schedule?_page_token=" + tokenValue, nurse)).isEqualTo(400);

    // Garbage and an offset-style token
    assertThat(get("/fhir/Schedule?_page_token=garbage", nurse)).isEqualTo(400);
    assertThat(get("/fhir/Schedule?_page_token=2", nurse)).isEqualTo(400);
    assertThat(get("/fhir/Schedule?_page_token=a.b", nurse)).isEqualTo(400);
  }

  @Test
  @DisplayName("S3: unsupported parameters and modifiers are 400 unsupported-parameter")
  void s3UnsupportedParametersAreRejected() throws Exception {
    String nurse = token("nurse-unsup", List.of("nurse"));
    identityLinks.links.put(
        "nurse-unsup", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));

    for (String query :
        List.of(
            "_offset=1",
            "status=active",
            "name=Quan",
            "_sort=id",
            "specialty:exact=cardiology",
            "specialty:not=cardiology",
            "actor:Practitioner=" + UUID.randomUUID(),
            "active:missing=true",
            "_count:exact=5")) {
      var res = getResponse("/fhir/Schedule?" + query, nurse);
      assertThat(res.statusCode()).as(query).isEqualTo(400);
      assertThat(res.body()).as(query).contains("unsupported-parameter");
    }
    // Rejected before authorization-dependent filtering for a doctor too
    UUID doc = UUID.randomUUID();
    identityLinks.links.put("doc-unsup", new IdentityLink("Practitioner", doc.toString(), false));
    var res = getResponse("/fhir/Schedule?bogus=1", token("doc-unsup", List.of("doctor")));
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("unsupported-parameter");
  }

  @Test
  @DisplayName("S3: invalid values of supported parameters are 400 validation-failed")
  void s3InvalidParameterValuesAreRejected() throws Exception {
    String nurse = token("nurse-val", List.of("nurse"));
    identityLinks.links.put(
        "nurse-val", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    UUID doc = UUID.randomUUID();
    for (String query :
        List.of(
            "active=maybe",
            "active=",
            "specialty=",
            "specialty=http://other.example.org/cs%7Ccardiology",
            "actor=Patient/" + doc,
            "actor=" + doc + "," + UUID.randomUUID(),
            "actor=Practitioner/" + doc + "&actor=Practitioner/" + UUID.randomUUID(),
            "_count=abc",
            "_count=-1",
            "_count=1&_count=2")) {
      var res = getResponse("/fhir/Schedule?" + query, nurse);
      assertThat(res.statusCode()).as(query).isEqualTo(400);
      assertThat(res.body()).as(query).contains("validation-failed");
    }
  }

  @Test
  @DisplayName("S3: actor, specialty and active filters, with a system-qualified specialty")
  void s3FiltersApply() throws Exception {
    String nurse = token("nurse-filter", List.of("nurse"));
    identityLinks.links.put(
        "nurse-filter", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    UUID docA = UUID.randomUUID();
    UUID docB = UUID.randomUUID();
    practitionerRoles.register(docA, "cardiology");
    practitionerRoles.register(docB, "neurology");
    assertThat(createSchedule(nurse, docA).statusCode()).isEqualTo(201);
    var inactive =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurse)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(scheduleJson(false, docB, "neurology")))
                .build());
    assertThat(inactive.statusCode()).isEqualTo(201);

    assertThat(entries("/fhir/Schedule?specialty=neurology", nurse)).isEqualTo(1);
    assertThat(
            entries(
                "/fhir/Schedule?specialty="
                    + java.net.URLEncoder.encode(
                        "https://shp.example.org/fhir/CodeSystem/specialty|cardiology",
                        StandardCharsets.UTF_8),
                nurse))
        .isEqualTo(1);
    assertThat(entries("/fhir/Schedule?active=true", nurse)).isEqualTo(1);
    assertThat(entries("/fhir/Schedule?active=false", nurse)).isEqualTo(1);
    assertThat(entries("/fhir/Schedule?actor=Practitioner/" + docB, nurse)).isEqualTo(1);
    assertThat(entries("/fhir/Schedule?actor=" + docA + "&active=false", nurse)).isZero();
  }

  @Test
  @DisplayName("TC-AC-02.3: the denied read of another doctor's schedule is audited as denied")
  void deniedScheduleReadIsAudited() throws Exception {
    UUID docA = UUID.randomUUID();
    UUID docB = UUID.randomUUID();
    practitionerRoles.register(docA, "cardiology");
    String nurse = token("nurse-audit", List.of("nurse"));
    identityLinks.links.put(
        "nurse-audit", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    String subDocB = "audit-doc-" + docB;
    identityLinks.links.put(subDocB, new IdentityLink("Practitioner", docB.toString(), false));
    String id =
        JSON.readTree(createSchedule(nurse, docA).body())
            .path("id")
            .asString()
            .replace("Schedule/", "");

    assertThat(get("/fhir/Schedule/" + id, token(subDocB, List.of("doctor")))).isEqualTo(403);

    var audits =
        jdbc.sql(
                """
                SELECT payload::text FROM outbox
                WHERE event_type = 'shp.audit.recorded.v1'
                  AND payload->'data'->>'outcome' = 'denied'
                  AND payload->'data'->'actor'->>'subject' = ?
                """)
            .param(subDocB)
            .query(String.class)
            .list();
    assertThat(audits).hasSize(1);
    JsonNode audit = JSON.readTree(audits.getFirst()).path("data");
    assertThat(audit.path("service").asString()).isEqualTo("scheduling");
    assertThat(audit.path("action").asString()).isEqualTo("read");
    assertThat(audit.path("actor").path("role").asString()).isEqualTo("doctor");
    assertThat(audit.path("resource").asString()).startsWith("Schedule");
    assertThat(audit.path("reason").asString()).isNotBlank();
    // No clinical content or search values in the record
    assertThat(audits.getFirst()).doesNotContain("cardiology");
  }

  private HttpResponse<String> getResponse(String path, String bearer) throws Exception {
    var b = HttpRequest.newBuilder(url(path)).GET();
    if (bearer != null) {
      b.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return send(b.build());
  }

  private int entries(String path, String bearer) throws Exception {
    var res = getResponse(path, bearer);
    assertThat(res.statusCode()).as(path).isEqualTo(200);
    return JSON.readTree(res.body()).path("entry").size();
  }

  private static String link(JsonNode bundle, String relation) {
    for (JsonNode l : bundle.path("link")) {
      if (relation.equals(l.path("relation").asString())) {
        return l.path("url").asString();
      }
    }
    return null;
  }

  private String scheduleJson(boolean active, UUID doctor, String specialty) {
    return """
    {"resourceType":"Schedule","active":%s,"actor":[{"reference":"Practitioner/%s"}],"specialty":[{"coding":[{"code":"%s"}]}]}
    """
        .formatted(active, doctor, specialty);
  }

  private HttpResponse<String> createSchedule(String bearer, UUID doctor) throws Exception {
    return send(
        HttpRequest.newBuilder(url("/fhir/Schedule"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(scheduleJson(true, doctor, "cardiology")))
            .build());
  }

  private int get(String path, String bearer) throws Exception {
    var b = HttpRequest.newBuilder(url(path)).GET();
    if (bearer != null) {
      b.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return send(b.build()).statusCode();
  }

  private int put(String path, String bearer, String ifMatch, String body) throws Exception {
    return send(HttpRequest.newBuilder(url(path))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
            .header(HttpHeaders.IF_MATCH, ifMatch)
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build())
        .statusCode();
  }
}
