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
import org.shp.web.authz.UnprotectedEndpoints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
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
 * SCH-21 (US-019): S17 {@code GET /fhir/Appointment/{id}/_history} end-to-end integration tests on
 * PostgreSQL 16 and RabbitMQ with real authentication, identity, authorization, and audit filters.
 * Synthetic IDs and subjects only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + AppointmentHistoryApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + AppointmentHistoryApiTest.TestSecurityConfig.AUDIENCE,
      "shp.events.validate-schema=true"
    })
@Testcontainers
@Import(AppointmentHistoryApiTest.TestSecurityConfig.class)
class AppointmentHistoryApiTest {

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

  private static final UUID PATIENT_ID = UUID.fromString("0192f8a4-1000-7000-8000-000000000101");
  private static final UUID DOCTOR_ID = UUID.fromString("0192f8a4-1000-7000-8000-000000000201");
  private static final UUID NURSE_ID = UUID.fromString("0192f8a4-1000-7000-8000-000000000301");
  private static final UUID ADMIN_ID = UUID.fromString("0192f8a4-1000-7000-8000-000000000401");
  private static final UUID DPO_ID = UUID.fromString("0192f8a4-1000-7000-8000-000000000501");

  private static final Instant T1 = Instant.parse("2026-11-18T08:00:00Z");
  private static final Instant T2 = Instant.parse("2026-11-19T07:45:00Z");
  private static final Instant T3 = Instant.parse("2026-11-19T08:30:00Z");

  @Autowired Environment env;
  @Autowired ApplicationContext ctx;
  @Autowired TestSecurityConfig.FakeIdentityLinkClient identityLinks;
  @Autowired JdbcClient jdbc;

  private UUID appointmentId;
  private String patientToken;
  private String doctorToken;
  private String nurseToken;
  private String adminToken;
  private String dpoToken;

  @TestConfiguration
  static class TestSecurityConfig {
    static final String ISSUER = "https://idp.test/realms/shp";
    static final String AUDIENCE = "shp-api";
    static final byte[] KEY =
        "test-only-history-key-32-bytes-minimum!".getBytes(StandardCharsets.UTF_8);

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
  void seed() {
    jdbc.sql(
            """
            TRUNCATE scheduling.appointment, scheduling.slot, scheduling.schedule_template,
                     scheduling.schedule, scheduling.resource_history, scheduling.outbox CASCADE
            """)
        .update();
    identityLinks.links.clear();
    identityLinks.links.put(
        "hist-patient-1", new IdentityLink("Patient", PATIENT_ID.toString(), false));
    identityLinks.links.put(
        "hist-doctor-1", new IdentityLink("Practitioner", DOCTOR_ID.toString(), false));
    identityLinks.links.put(
        "hist-nurse-1", new IdentityLink("Practitioner", NURSE_ID.toString(), false));
    identityLinks.links.put(
        "hist-admin-1", new IdentityLink("Practitioner", ADMIN_ID.toString(), false));
    identityLinks.links.put(
        "hist-dpo-1", new IdentityLink("Practitioner", DPO_ID.toString(), false));

    patientToken = token("hist-patient-1", List.of("patient"), false);
    doctorToken = token("hist-doctor-1", List.of("doctor"), true);
    nurseToken = token("hist-nurse-1", List.of("nurse"), true);
    adminToken = token("hist-admin-1", List.of("admin"), true);
    dpoToken = token("hist-dpo-1", List.of("dpo"), true);

    UUID scheduleId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    appointmentId = UUID.randomUUID();
    Instant slotStart = Instant.parse("2026-11-19T08:00:00Z");
    Instant slotEnd = Instant.parse("2026-11-19T08:15:00Z");

    jdbc.sql(
            """
            INSERT INTO scheduling.schedule (
                id, version_id, last_updated, active, practitioner_id, specialty,
                horizon_start, horizon_end, content
            ) VALUES (?, 1, ?, true, ?, 'cardiology', ?, ?, '{}'::jsonb)
            """)
        .params(
            scheduleId,
            Timestamp.from(T1),
            DOCTOR_ID,
            java.sql.Date.valueOf(LocalDate.of(2026, 11, 18)),
            java.sql.Date.valueOf(LocalDate.of(2026, 12, 18)))
        .update();

    jdbc.sql(
            """
            INSERT INTO scheduling.slot (
                id, version_id, last_updated, schedule_id, practitioner_id, specialty,
                status, start_at, end_at, content
            ) VALUES (?, 2, ?, ?, ?, 'cardiology', 'busy', ?, ?, '{}'::jsonb)
            """)
        .params(
            slotId,
            Timestamp.from(T1),
            scheduleId,
            DOCTOR_ID,
            Timestamp.from(slotStart),
            Timestamp.from(slotEnd))
        .update();

    String v1Json =
        appointmentJson(
            appointmentId, 1, T1, Appointment.AppointmentStatus.BOOKED, slotId, slotStart, slotEnd);
    String v2Json =
        appointmentJson(
            appointmentId,
            2,
            T2,
            Appointment.AppointmentStatus.ARRIVED,
            slotId,
            slotStart,
            slotEnd);
    String v3Json =
        appointmentJson(
            appointmentId,
            3,
            T3,
            Appointment.AppointmentStatus.FULFILLED,
            slotId,
            slotStart,
            slotEnd);

    jdbc.sql(
            """
            INSERT INTO scheduling.appointment (
                id, version_id, last_updated, status, slot_id, patient_id, practitioner_id,
                specialty, start_at, end_at, channel, booked_by, content
            ) VALUES (?, 3, ?, 'fulfilled', ?, ?, ?, 'cardiology', ?, ?, 'web', 'hist-patient-1', ?::jsonb)
            """)
        .params(
            appointmentId,
            Timestamp.from(T3),
            slotId,
            PATIENT_ID,
            DOCTOR_ID,
            Timestamp.from(slotStart),
            Timestamp.from(slotEnd),
            v3Json)
        .update();

    insertHistory(appointmentId, 1, T1, "create", "hist-patient-1", v1Json);
    insertHistory(appointmentId, 2, T2, "update", "hist-nurse-1", v2Json);
    insertHistory(appointmentId, 3, T3, "update", "system-clinical", v3Json);
  }

  private void insertHistory(
      UUID id,
      int versionId,
      Instant lastUpdated,
      String operation,
      String changedBy,
      String json) {
    jdbc.sql(
            """
            INSERT INTO scheduling.resource_history (
                resource_type, id, version_id, last_updated, operation, changed_by, content
            ) VALUES ('Appointment', ?, ?, ?, ?, ?, ?::jsonb)
            """)
        .params(id, versionId, Timestamp.from(lastUpdated), operation, changedBy, json)
        .update();
  }

  private static String appointmentJson(
      UUID id,
      int versionId,
      Instant lastUpdated,
      Appointment.AppointmentStatus status,
      UUID slotId,
      Instant start,
      Instant end) {
    var appt = new Appointment();
    appt.setId(id.toString());
    appt.getMeta().setVersionId(Integer.toString(versionId)).setLastUpdated(Date.from(lastUpdated));
    appt.setStatus(status);
    appt.addSpecialty().addCoding(new Coding().setSystem(SPECIALTY_SYSTEM).setCode("cardiology"));
    appt.setStart(Date.from(start));
    appt.setEnd(Date.from(end));
    appt.setCreated(Date.from(T1));
    appt.addSlot(new Reference("Slot/" + slotId));
    appt.addParticipant()
        .setActor(new Reference("Patient/" + PATIENT_ID))
        .setStatus(Appointment.ParticipationStatus.ACCEPTED);
    appt.addParticipant()
        .setActor(new Reference("Practitioner/" + DOCTOR_ID))
        .setStatus(Appointment.ParticipationStatus.ACCEPTED);
    appt.addExtension(new Extension(BOOKING_CHANNEL_URL, new CodeType("web")));
    return FhirJson.serialize(appt);
  }

  private static String token(String subject, List<String> roles, boolean mfa) {
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

  private HttpResponse<String> getHistory(String rawId, String bearer) throws Exception {
    URI uri =
        URI.create(
            "http://localhost:"
                + env.getProperty("local.server.port")
                + "/fhir/Appointment/"
                + rawId
                + "/_history");
    var builder = HttpRequest.newBuilder(uri).GET();
    if (bearer != null) {
      builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    try (var client = HttpClient.newHttpClient()) {
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String errorCode(HttpResponse<String> response) {
    return JSON.readTree(response.body())
        .path("issue")
        .path(0)
        .path("details")
        .path("coding")
        .path(0)
        .path("code")
        .asString();
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
  @DisplayName("Every endpoint declares its @RequireShpAuthz rule")
  void everyEndpointDeclaresItsAuthorizationRule() {
    assertThat(UnprotectedEndpoints.find(ctx)).isEmpty();
  }

  @Test
  @DisplayName("S17: Nurse and Admin retrieve all Appointment versions newest first")
  void nurseAndAdminRetrieveAllVersionsNewestFirst() throws Exception {
    for (String roleToken : List.of(nurseToken, adminToken)) {
      var res = getHistory(appointmentId.toString(), roleToken);

      assertThat(res.statusCode()).isEqualTo(200);
      assertThat(res.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
          .contains("application/fhir+json");

      JsonNode bundle = JSON.readTree(res.body());
      assertThat(bundle.path("resourceType").asString()).isEqualTo("Bundle");
      assertThat(bundle.path("type").asString()).isEqualTo("history");
      assertThat(bundle.path("total").asInt()).isEqualTo(3);

      JsonNode entries = bundle.path("entry");
      assertThat(entries).hasSize(3);

      // Version 3 (newest): update -> fulfilled
      JsonNode e0 = entries.get(0);
      assertThat(e0.path("fullUrl").asString())
          .isEqualTo("Appointment/" + appointmentId + "/_history/3");
      assertThat(e0.path("resource").path("resourceType").asString()).isEqualTo("Appointment");
      assertThat(e0.path("resource").path("id").asString()).isEqualTo(appointmentId.toString());
      assertThat(e0.path("resource").path("status").asString()).isEqualTo("fulfilled");
      assertThat(e0.path("resource").path("meta").path("versionId").asString()).isEqualTo("3");
      assertThat(Instant.parse(e0.path("resource").path("meta").path("lastUpdated").asString()))
          .isEqualTo(T3);
      assertThat(e0.path("resource").path("meta").path("source").asString())
          .isEqualTo("system-clinical");
      assertThat(e0.path("request").path("method").asString()).isEqualTo("PUT");
      assertThat(e0.path("request").path("url").asString())
          .isEqualTo("Appointment/" + appointmentId);
      assertThat(e0.path("response").path("status").asString()).isEqualTo("200");
      assertThat(e0.path("response").path("etag").asString()).isEqualTo("W/\"3\"");
      assertThat(Instant.parse(e0.path("response").path("lastModified").asString())).isEqualTo(T3);

      // Version 2: update -> arrived
      JsonNode e1 = entries.get(1);
      assertThat(e1.path("fullUrl").asString())
          .isEqualTo("Appointment/" + appointmentId + "/_history/2");
      assertThat(e1.path("resource").path("status").asString()).isEqualTo("arrived");
      assertThat(e1.path("resource").path("meta").path("versionId").asString()).isEqualTo("2");
      assertThat(e1.path("resource").path("meta").path("source").asString())
          .isEqualTo("hist-nurse-1");
      assertThat(e1.path("request").path("method").asString()).isEqualTo("PUT");
      assertThat(e1.path("request").path("url").asString())
          .isEqualTo("Appointment/" + appointmentId);
      assertThat(e1.path("response").path("status").asString()).isEqualTo("200");
      assertThat(e1.path("response").path("etag").asString()).isEqualTo("W/\"2\"");
      assertThat(Instant.parse(e1.path("response").path("lastModified").asString())).isEqualTo(T2);

      // Version 1 (oldest): create -> booked
      JsonNode e2 = entries.get(2);
      assertThat(e2.path("fullUrl").asString())
          .isEqualTo("Appointment/" + appointmentId + "/_history/1");
      assertThat(e2.path("resource").path("status").asString()).isEqualTo("booked");
      assertThat(e2.path("resource").path("meta").path("versionId").asString()).isEqualTo("1");
      assertThat(e2.path("resource").path("meta").path("source").asString())
          .isEqualTo("hist-patient-1");
      assertThat(e2.path("request").path("method").asString()).isEqualTo("POST");
      assertThat(e2.path("request").path("url").asString()).isEqualTo("Appointment");
      assertThat(e2.path("response").path("status").asString()).isEqualTo("201");
      assertThat(e2.path("response").path("etag").asString()).isEqualTo("W/\"1\"");
      assertThat(Instant.parse(e2.path("response").path("lastModified").asString())).isEqualTo(T1);
    }
  }

  @Test
  @DisplayName("S17 authz matrix: Patient, Doctor, and DPO receive 403; unauthenticated gets 401")
  void historyAuthorizationMatrix() throws Exception {
    for (String deniedToken : List.of(patientToken, doctorToken, dpoToken)) {
      var res = getHistory(appointmentId.toString(), deniedToken);
      assertThat(res.statusCode()).isEqualTo(403);
      assertThat(errorCode(res)).isEqualTo("forbidden");
    }

    var unauthenticated = getHistory(appointmentId.toString(), null);
    assertThat(unauthenticated.statusCode()).isEqualTo(401);
    assertThat(errorCode(unauthenticated)).isEqualTo("unauthenticated");
  }

  @Test
  @DisplayName("S17 audit events record both success and denied decisions with patientId")
  void auditEventsRecordAllowAndDenyWithPatientId() throws Exception {
    assertThat(getHistory(appointmentId.toString(), nurseToken).statusCode()).isEqualTo(200);
    assertThat(getHistory(appointmentId.toString(), patientToken).statusCode()).isEqualTo(403);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          List<JsonNode> events = auditEvents();
          assertThat(events).hasSizeGreaterThanOrEqualTo(2);
          assertThat(events)
              .anySatisfy(evt -> {
                JsonNode data = evt.path("data");
                assertThat(data.path("action").asString()).isEqualTo("read");
                assertThat(data.path("outcome").asString()).isEqualTo("success");
                assertThat(data.path("actor").path("role").asString()).isEqualTo("nurse");
                assertThat(data.path("patientId").asString()).isEqualTo(PATIENT_ID.toString());
                assertThat(data.path("resource").asString())
                    .isEqualTo("AppointmentHistory/" + appointmentId);
              });
          assertThat(events)
              .anySatisfy(evt -> {
                JsonNode data = evt.path("data");
                assertThat(data.path("action").asString()).isEqualTo("read");
                assertThat(data.path("outcome").asString()).isEqualTo("denied");
                assertThat(data.path("actor").path("role").asString()).isEqualTo("patient");
                assertThat(data.path("patientId").asString()).isEqualTo(PATIENT_ID.toString());
                assertThat(data.path("resource").asString())
                    .isEqualTo("AppointmentHistory/" + appointmentId);
              });
        });
  }

  @Test
  @DisplayName("S17 returns 404 for unknown or malformed IDs (authorized) and 403 for denied roles")
  void missingOrMalformedIdReturns404ForAuthorizedRolesAnd403ForDeniedRoles() throws Exception {
    UUID missingId = UUID.fromString("0192f8a4-9999-7000-8000-999999999999");

    var nurseMissing = getHistory(missingId.toString(), nurseToken);
    assertThat(nurseMissing.statusCode()).isEqualTo(404);
    assertThat(errorCode(nurseMissing)).isEqualTo("not-found");

    var adminMissing = getHistory(missingId.toString(), adminToken);
    assertThat(adminMissing.statusCode()).isEqualTo(404);
    assertThat(errorCode(adminMissing)).isEqualTo("not-found");

    var nurseMalformed = getHistory("not-a-uuid", nurseToken);
    assertThat(nurseMalformed.statusCode()).isEqualTo(404);
    assertThat(errorCode(nurseMalformed)).isEqualTo("not-found");

    var patientMissing = getHistory(missingId.toString(), patientToken);
    assertThat(patientMissing.statusCode()).isEqualTo(403);
    assertThat(errorCode(patientMissing)).isEqualTo("forbidden");

    var doctorMalformed = getHistory("not-a-uuid", doctorToken);
    assertThat(doctorMalformed.statusCode()).isEqualTo(403);
    assertThat(errorCode(doctorMalformed)).isEqualTo("forbidden");
  }
}
