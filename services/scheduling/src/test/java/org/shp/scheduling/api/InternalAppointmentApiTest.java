package org.shp.scheduling.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.fhir.FhirJson;
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
 * SCH-22 (US-022): End-to-end integration tests for S18 {@code GET /internal/v1/appointments/{id}}
 * on PostgreSQL 16 and RabbitMQ with service workload-identity token enforcement and system audit.
 * Synthetic IDs and subjects only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + InternalAppointmentApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + InternalAppointmentApiTest.TestSecurityConfig.AUDIENCE,
      "shp.events.validate-schema=true"
    })
@Testcontainers
@Import(InternalAppointmentApiTest.TestSecurityConfig.class)
class InternalAppointmentApiTest {

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
  private static final String SPECIALTY_SYSTEM =
      "https://shp.example.org/fhir/CodeSystem/specialty";
  private static final String BOOKING_CHANNEL_URL =
      "https://shp.example.org/fhir/StructureDefinition/booking-channel";

  private static final UUID PATIENT_ID = UUID.fromString("0192f8a4-4000-7000-8000-000000000101");
  private static final UUID DOCTOR_ID = UUID.fromString("0192f8a4-4000-7000-8000-000000000201");

  private static final Instant START_AT = Instant.parse("2026-12-01T08:00:00Z");
  private static final Instant END_AT = Instant.parse("2026-12-01T08:15:00Z");

  @Autowired Environment env;
  @Autowired JdbcClient jdbc;

  private UUID appointmentId;

  @TestConfiguration
  static class TestSecurityConfig {
    static final String ISSUER = "https://idp.test/realms/shp";
    static final String AUDIENCE = "shp-api";
    static final byte[] KEY =
        "test-only-internal-appt-key-32-bytes!!".getBytes(StandardCharsets.UTF_8);

    @Bean
    @Primary
    JwtDecoder testJwtDecoder(ShpAuthProperties props, Clock clock) {
      var decoder =
          NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
              .macAlgorithm(MacAlgorithm.HS256)
              .build();
      decoder.setJwtValidator(ShpJwt.validator(props, clock));
      return decoder;
    }

    @Bean
    @Primary
    IdentityLinkClient fakeIdentityLinkClient() {
      Map<String, IdentityLink> links = new ConcurrentHashMap<>();
      return subject -> Optional.ofNullable(links.get(subject));
    }
  }

  @BeforeEach
  void seed() {
    jdbc.sql(
            """
            TRUNCATE scheduling.appointment, scheduling.slot, scheduling.schedule_template,
                     scheduling.schedule, scheduling.resource_history, scheduling.outbox CASCADE
            """)
        .update();

    UUID scheduleId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    appointmentId = UUID.randomUUID();

    jdbc.sql(
            """
            INSERT INTO scheduling.schedule (
                id, version_id, last_updated, active, practitioner_id, specialty,
                horizon_start, horizon_end, content
            ) VALUES (?, 1, now(), true, ?, 'cardiology', ?, ?, '{}'::jsonb)
            """)
        .params(
            scheduleId,
            DOCTOR_ID,
            java.sql.Date.valueOf(LocalDate.of(2026, 12, 1)),
            java.sql.Date.valueOf(LocalDate.of(2026, 12, 31)))
        .update();

    jdbc.sql(
            """
            INSERT INTO scheduling.slot (
                id, version_id, last_updated, schedule_id, practitioner_id, specialty,
                status, start_at, end_at, content
            ) VALUES (?, 2, now(), ?, ?, 'cardiology', 'busy', ?, ?, '{}'::jsonb)
            """)
        .params(slotId, scheduleId, DOCTOR_ID, Timestamp.from(START_AT), Timestamp.from(END_AT))
        .update();

    Appointment appt = new Appointment();
    appt.setId(appointmentId.toString());
    appt.getMeta().setVersionId("2").setLastUpdated(Date.from(START_AT));
    appt.setStatus(Appointment.AppointmentStatus.ARRIVED);
    appt.addSpecialty().addCoding(new Coding().setSystem(SPECIALTY_SYSTEM).setCode("cardiology"));
    appt.setStart(Date.from(START_AT));
    appt.setEnd(Date.from(END_AT));
    appt.setCreated(Date.from(START_AT));
    appt.addSlot(new Reference("Slot/" + slotId));
    appt.addParticipant()
        .setActor(new Reference("Patient/" + PATIENT_ID))
        .setStatus(Appointment.ParticipationStatus.ACCEPTED);
    appt.addParticipant()
        .setActor(new Reference("Practitioner/" + DOCTOR_ID))
        .setStatus(Appointment.ParticipationStatus.ACCEPTED);
    appt.addExtension(new Extension(BOOKING_CHANNEL_URL, new CodeType("web")));
    String json = FhirJson.serialize(appt);

    jdbc.sql(
            """
            INSERT INTO scheduling.appointment (
                id, version_id, last_updated, status, slot_id, patient_id, practitioner_id,
                specialty, start_at, end_at, channel, booked_by, content
            ) VALUES (?, 2, now(), 'arrived', ?, ?, ?, 'cardiology', ?, ?, 'web', 'pat-sub-1', ?::jsonb)
            """)
        .params(
            appointmentId,
            slotId,
            PATIENT_ID,
            DOCTOR_ID,
            Timestamp.from(START_AT),
            Timestamp.from(END_AT),
            json)
        .update();
  }

  private static String serviceToken(String subject, String azp, List<String> roles) {
    return buildToken(subject, azp, roles, false);
  }

  private static String userToken(String subject, String role, boolean mfa) {
    return buildToken(subject, "shp-web", List.of(role), mfa);
  }

  private static String buildToken(String subject, String azp, List<String> roles, boolean mfa) {
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(TestSecurityConfig.ISSUER)
              .subject(subject)
              .audience(TestSecurityConfig.AUDIENCE)
              .claim("roles", roles)
              .issueTime(Date.from(now.minusSeconds(60)))
              .notBeforeTime(Date.from(now.minusSeconds(60)))
              .expirationTime(Date.from(now.plusSeconds(600)));
      if (azp != null) {
        claims.claim("azp", azp);
      }
      if (mfa) {
        claims.claim("amr", List.of("pwd", "mfa"));
      }
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
      jwt.sign(new MACSigner(TestSecurityConfig.KEY));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpResponse<String> call(String method, String rawId, String bearer) throws Exception {
    URI uri =
        URI.create(
            "http://localhost:"
                + env.getProperty("local.server.port")
                + "/internal/v1/appointments/"
                + rawId);
    var builder = HttpRequest.newBuilder(uri);
    if ("POST".equals(method)) {
      builder.POST(HttpRequest.BodyPublishers.ofString("{}"));
    } else {
      builder.GET();
    }
    if (bearer != null) {
      builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    try (var client = HttpClient.newHttpClient()) {
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private List<JsonNode> auditEvents() {
    return jdbc
        .sql(
            """
            SELECT payload::text
              FROM scheduling.outbox
             WHERE event_type = 'shp.audit.recorded.v1'
             ORDER BY occurred_at ASC
            """)
        .query(String.class)
        .list()
        .stream()
        .map(JSON::readTree)
        .toList();
  }

  @Test
  @DisplayName(
      "S18: clinical and ml service tokens read full Appointment with actor.role=system audit")
  void clinicalAndMlServiceTokensReadAppointmentAndEmitSystemAudit() throws Exception {
    String clinicalToken =
        serviceToken("svc-clinical-sa", "svc-clinical", List.of("internal.scheduling"));
    String mlToken = serviceToken("svc-ml-sa", "svc-ml", List.of("internal.scheduling"));

    for (String token : List.of(clinicalToken, mlToken)) {
      var res = call("GET", appointmentId.toString(), token);
      assertThat(res.statusCode()).isEqualTo(200);
      assertThat(res.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
          .contains("application/fhir+json");
      assertThat(res.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"2\"");
      assertThat(res.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");

      JsonNode body = JSON.readTree(res.body());
      assertThat(body.path("resourceType").asString()).isEqualTo("Appointment");
      assertThat(body.path("id").asString()).isEqualTo(appointmentId.toString());
      assertThat(body.path("status").asString()).isEqualTo("arrived");
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          List<JsonNode> events = auditEvents();
          assertThat(events).hasSize(2);
          for (String expectedSubject : List.of("svc-clinical-sa", "svc-ml-sa")) {
            assertThat(events)
                .anySatisfy(evt -> {
                  JsonNode data = evt.path("data");
                  assertThat(data.path("action").asString()).isEqualTo("read");
                  assertThat(data.path("outcome").asString()).isEqualTo("success");
                  assertThat(data.path("actor").path("role").asString()).isEqualTo("system");
                  assertThat(data.path("actor").path("subject").asString())
                      .isEqualTo(expectedSubject);
                  assertThat(data.path("purposeOfUse").asString()).isEqualTo("HOPERAT");
                  assertThat(data.path("patientId").asString()).isEqualTo(PATIENT_ID.toString());
                  assertThat(data.path("resource").asString())
                      .isEqualTo("Appointment/" + appointmentId);
                });
          }
        });
  }

  @Test
  @DisplayName("TC-NFR-SEC2: Other services and all user roles get 403 forbidden with system audit")
  void otherServicesAndAllUserRolesAreDeniedWith403AndSystemAudit() throws Exception {
    List<String> forbiddenTokens =
        List.of(
            serviceToken("svc-ai-sa", "svc-ai", List.of("internal.scheduling")),
            serviceToken("svc-doc-sa", "svc-document", List.of("internal.scheduling")),
            serviceToken("svc-ntf-sa", "svc-notification", List.of("internal.scheduling")),
            serviceToken("svc-aud-sa", "svc-audit", List.of("internal.scheduling")),
            serviceToken("svc-clinical-sa", "svc-clinical", List.of("internal.clinical")),
            serviceToken("svc-no-azp-sa", null, List.of("internal.scheduling")),
            userToken("pat-sub-1", "patient", false),
            userToken("doc-sub-1", "doctor", true),
            userToken("nur-sub-1", "nurse", true),
            userToken("adm-sub-1", "admin", true),
            userToken("dpo-sub-1", "dpo", true));

    for (String deniedToken : forbiddenTokens) {
      var res = call("GET", appointmentId.toString(), deniedToken);
      assertThat(res.statusCode()).isEqualTo(403);
      assertThat(res.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
          .contains("application/problem+json");
      assertThat(JSON.readTree(res.body()).path("code").asString()).isEqualTo("forbidden");
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          List<JsonNode> events = auditEvents();
          assertThat(events).hasSize(forbiddenTokens.size());
          assertThat(events)
              .allSatisfy(evt -> {
                JsonNode data = evt.path("data");
                assertThat(data.path("action").asString()).isEqualTo("read");
                assertThat(data.path("outcome").asString()).isEqualTo("denied");
                assertThat(data.path("reason").asString()).isEqualTo("role-not-allowed");
                assertThat(data.path("actor").path("role").asString()).isEqualTo("system");
                assertThat(data.path("patientId").asString()).isEqualTo(PATIENT_ID.toString());
                assertThat(data.path("resource").asString())
                    .isEqualTo("Appointment/" + appointmentId);
              });
        });
  }

  @Test
  @DisplayName(
      "S18: Unauthenticated returns 401; missing/malformed ID returns 404; POST returns 400")
  void unauthenticatedMissingAndInvalidMethodRequests() throws Exception {
    var unauth = call("GET", appointmentId.toString(), null);
    assertThat(unauth.statusCode()).isEqualTo(401);
    assertThat(unauth.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
        .contains("application/problem+json");
    assertThat(JSON.readTree(unauth.body()).path("code").asString()).isEqualTo("unauthenticated");

    String clinicalToken =
        serviceToken("svc-clinical-sa", "svc-clinical", List.of("internal.scheduling"));

    UUID missingId = UUID.fromString("0192f8a4-9999-7000-8000-999999999999");
    var missing = call("GET", missingId.toString(), clinicalToken);
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(missing.body()).path("code").asString()).isEqualTo("not-found");

    var malformed = call("GET", "not-a-uuid", clinicalToken);
    assertThat(malformed.statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(malformed.body()).path("code").asString()).isEqualTo("not-found");

    var wrongMethod = call("POST", appointmentId.toString(), clinicalToken);
    assertThat(wrongMethod.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(wrongMethod.body()).path("code").asString())
        .isEqualTo("validation-failed");
  }
}
