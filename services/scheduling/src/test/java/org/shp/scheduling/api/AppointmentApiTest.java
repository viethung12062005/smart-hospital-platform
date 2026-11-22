package org.shp.scheduling.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.shp.scheduling.domain.AppointmentSearchCriteria;
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
 * SCH-17 (US-013, US-018, US-019): End-to-end integration tests for S12 {@code GET
 * /fhir/Appointment/{id}} and S13 {@code GET /fhir/Appointment} on PostgreSQL 16 and RabbitMQ with
 * real authentication, identity, authorization, keyset pagination, and audit emission. Synthetic
 * IDs and subjects only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + AppointmentApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + AppointmentApiTest.TestSecurityConfig.AUDIENCE,
      "shp.paging.token-key=test-only-page-token-key-32-bytes-minimum!!",
      "shp.events.validate-schema=true"
    })
@Testcontainers
@Import(AppointmentApiTest.TestSecurityConfig.class)
class AppointmentApiTest {

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
  private static final String BOOKING_CHANNEL_URL =
      "https://shp.example.org/fhir/StructureDefinition/booking-channel";

  private static final UUID PATIENT_A = UUID.fromString("0192f8a4-2000-7000-8000-00000000000a");
  private static final UUID PATIENT_B = UUID.fromString("0192f8a4-2000-7000-8000-00000000000b");
  private static final UUID DOCTOR_1 = UUID.fromString("0192f8a4-2000-7000-8000-0000000000d1");
  private static final UUID DOCTOR_2 = UUID.fromString("0192f8a4-2000-7000-8000-0000000000d2");
  private static final UUID NURSE_1 = UUID.fromString("0192f8a4-2000-7000-8000-0000000000e1");
  private static final UUID ADMIN_1 = UUID.fromString("0192f8a4-2000-7000-8000-0000000000f1");
  private static final UUID DPO_1 = UUID.fromString("0192f8a4-2000-7000-8000-0000000000f2");

  private static final Instant T_DAY1_08 = Instant.parse("2026-12-01T01:00:00Z");
  private static final Instant T_DAY2_09 = Instant.parse("2026-12-02T02:00:00Z");
  private static final Instant T_DAY3_10 = Instant.parse("2026-12-03T03:00:00Z");
  private static final Instant T_DAY4_11 = Instant.parse("2026-12-04T04:00:00Z");
  private static final Instant T_DAY5_08 = Instant.parse("2026-12-05T01:00:00Z");

  @Autowired Environment env;
  @Autowired ApplicationContext ctx;
  @Autowired TestSecurityConfig.FakeIdentityLinkClient identityLinks;
  @Autowired JdbcClient jdbc;

  private UUID apptA1Booked;
  private UUID apptA2Arrived;
  private UUID apptA3Cancelled;
  private UUID apptA4WithDoc2;
  private UUID apptB1Fulfilled;
  private UUID apptB2SameTimeLow;
  private UUID apptB3SameTimeHigh;

  private String patientAToken;
  private String doctor1Token;
  private String nurseToken;
  private String adminToken;
  private String dpoToken;

  @TestConfiguration
  static class TestSecurityConfig {
    static final String ISSUER = "https://idp.test/realms/shp";
    static final String AUDIENCE = "shp-api";
    static final byte[] KEY =
        "test-only-appt-key-32-bytes-minimum!".getBytes(StandardCharsets.UTF_8);

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
        "appt-patient-a", new IdentityLink("Patient", PATIENT_A.toString(), false));
    identityLinks.links.put(
        "appt-patient-b", new IdentityLink("Patient", PATIENT_B.toString(), false));
    identityLinks.links.put(
        "appt-doctor-1", new IdentityLink("Practitioner", DOCTOR_1.toString(), false));
    identityLinks.links.put(
        "appt-doctor-2", new IdentityLink("Practitioner", DOCTOR_2.toString(), false));
    identityLinks.links.put(
        "appt-nurse-1", new IdentityLink("Practitioner", NURSE_1.toString(), false));
    identityLinks.links.put(
        "appt-admin-1", new IdentityLink("Practitioner", ADMIN_1.toString(), false));
    identityLinks.links.put(
        "appt-dpo-1", new IdentityLink("Practitioner", DPO_1.toString(), false));

    patientAToken = token("appt-patient-a", List.of("patient"), false);
    doctor1Token = token("appt-doctor-1", List.of("doctor"), true);
    nurseToken = token("appt-nurse-1", List.of("nurse"), true);
    adminToken = token("appt-admin-1", List.of("admin"), true);
    dpoToken = token("appt-dpo-1", List.of("dpo"), true);

    UUID scheduleDoc1Cardio = insertSchedule(DOCTOR_1, "cardiology");
    UUID scheduleDoc1Internal = insertSchedule(DOCTOR_1, "internal-medicine");
    UUID scheduleDoc2Derm = insertSchedule(DOCTOR_2, "dermatology");

    apptA1Booked =
        insertAppointment(
            UUID.fromString("0192f8a4-3000-7000-8000-000000000001"),
            1,
            scheduleDoc1Cardio,
            PATIENT_A,
            DOCTOR_1,
            "cardiology",
            "booked",
            T_DAY1_08,
            null);
    apptA2Arrived =
        insertAppointment(
            UUID.fromString("0192f8a4-3000-7000-8000-000000000002"),
            2,
            scheduleDoc1Cardio,
            PATIENT_A,
            DOCTOR_1,
            "cardiology",
            "arrived",
            T_DAY3_10,
            null);
    apptA3Cancelled =
        insertAppointment(
            UUID.fromString("0192f8a4-3000-7000-8000-000000000003"),
            2,
            scheduleDoc1Cardio,
            PATIENT_A,
            DOCTOR_1,
            "cardiology",
            "cancelled",
            T_DAY4_11,
            "patient-request");
    apptA4WithDoc2 =
        insertAppointment(
            UUID.fromString("0192f8a4-3000-7000-8000-000000000004"),
            1,
            scheduleDoc2Derm,
            PATIENT_A,
            DOCTOR_2,
            "dermatology",
            "booked",
            T_DAY5_08,
            null);
    apptB1Fulfilled =
        insertAppointment(
            UUID.fromString("0192f8a4-3000-7000-8000-000000000005"),
            3,
            scheduleDoc2Derm,
            PATIENT_B,
            DOCTOR_2,
            "dermatology",
            "fulfilled",
            T_DAY2_09,
            null);
    // Two appointments for Doctor 1 + Patient B sharing T_DAY2_09 to verify keyset tie-breaking.
    apptB2SameTimeLow =
        insertAppointment(
            UUID.fromString("0192f8a4-3000-7000-8000-000000000006"),
            1,
            scheduleDoc1Cardio,
            PATIENT_B,
            DOCTOR_1,
            "cardiology",
            "booked",
            T_DAY2_09,
            null);
    apptB3SameTimeHigh =
        insertAppointment(
            UUID.fromString("0192f8a4-3000-7000-8000-000000000007"),
            1,
            scheduleDoc1Internal,
            PATIENT_B,
            DOCTOR_1,
            "internal-medicine",
            "booked",
            T_DAY2_09,
            null);
  }

  private UUID insertSchedule(UUID practitionerId, String specialty) {
    UUID scheduleId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO scheduling.schedule (
                id, version_id, last_updated, active, practitioner_id, specialty,
                horizon_start, horizon_end, content
            ) VALUES (?, 1, now(), true, ?, ?, ?, ?, '{}'::jsonb)
            """)
        .params(
            scheduleId,
            practitionerId,
            specialty,
            java.sql.Date.valueOf(LocalDate.of(2026, 12, 1)),
            java.sql.Date.valueOf(LocalDate.of(2026, 12, 31)))
        .update();
    return scheduleId;
  }

  private UUID insertAppointment(
      UUID appointmentId,
      int versionId,
      UUID scheduleId,
      UUID patientId,
      UUID practitionerId,
      String specialty,
      String status,
      Instant startAt,
      String cancelReason) {
    UUID slotId = UUID.randomUUID();
    Instant endAt = startAt.plusSeconds(900);
    String slotStatus = "cancelled".equals(status) ? "free" : "busy";
    jdbc.sql(
            """
            INSERT INTO scheduling.slot (
                id, version_id, last_updated, schedule_id, practitioner_id, specialty,
                status, start_at, end_at, content
            ) VALUES (?, 1, now(), ?, ?, ?, ?, ?, ?, '{}'::jsonb)
            """)
        .params(
            slotId,
            scheduleId,
            practitionerId,
            specialty,
            slotStatus,
            Timestamp.from(startAt),
            Timestamp.from(endAt))
        .update();

    Appointment appt = new Appointment();
    appt.setId(appointmentId.toString());
    appt.getMeta().setVersionId(Integer.toString(versionId)).setLastUpdated(Date.from(T_DAY1_08));
    appt.setStatus(Appointment.AppointmentStatus.fromCode(status));
    appt.addSpecialty()
        .addCoding(
            new Coding().setSystem(AppointmentSearchCriteria.SPECIALTY_SYSTEM).setCode(specialty));
    appt.setStart(Date.from(startAt));
    appt.setEnd(Date.from(endAt));
    appt.setCreated(Date.from(T_DAY1_08));
    appt.addSlot(new Reference("Slot/" + slotId));
    appt.addParticipant()
        .setActor(new Reference("Patient/" + patientId))
        .setStatus(Appointment.ParticipationStatus.ACCEPTED);
    appt.addParticipant()
        .setActor(new Reference("Practitioner/" + practitionerId))
        .setStatus(Appointment.ParticipationStatus.ACCEPTED);
    appt.addExtension(new Extension(BOOKING_CHANNEL_URL, new CodeType("web")));
    String json = FhirJson.serialize(appt);

    jdbc.sql(
            """
            INSERT INTO scheduling.appointment (
                id, version_id, last_updated, status, slot_id, patient_id, practitioner_id,
                specialty, start_at, end_at, channel, booked_by, cancel_reason, content
            ) VALUES (?, ?, now(), ?, ?, ?, ?, ?, ?, ?, 'web', 'appt-patient-a', ?, ?::jsonb)
            """)
        .params(
            appointmentId,
            versionId,
            status,
            slotId,
            patientId,
            practitionerId,
            specialty,
            Timestamp.from(startAt),
            Timestamp.from(endAt),
            cancelReason,
            json)
        .update();
    return appointmentId;
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

  private HttpResponse<String> get(String pathAndQuery, String bearer) throws Exception {
    URI uri = URI.create("http://localhost:" + env.getProperty("local.server.port") + pathAndQuery);
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

  private static List<String> entryIds(HttpResponse<String> response) {
    JsonNode entries = JSON.readTree(response.body()).path("entry");
    List<String> ids = new ArrayList<>();
    for (JsonNode entry : entries) {
      ids.add(entry.path("resource").path("id").asString());
    }
    return ids;
  }

  private static String linkUrl(HttpResponse<String> response, String relation) {
    for (JsonNode link : JSON.readTree(response.body()).path("link")) {
      if (relation.equals(link.path("relation").asString())) {
        return link.path("url").asString();
      }
    }
    return null;
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
  @DisplayName("TC-NFR-SEC2: S12 and S13 enforce role permissions across all 5 roles and audit")
  void s12AndS13RolePermissions() throws Exception {
    // S12: Patient reads own appointment -> 200 with ETag
    var patOwn = get("/fhir/Appointment/" + apptA1Booked, patientAToken);
    assertThat(patOwn.statusCode()).isEqualTo(200);
    assertThat(patOwn.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
        .contains("application/fhir+json");
    assertThat(patOwn.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"1\"");
    assertThat(JSON.readTree(patOwn.body()).path("id").asString())
        .isEqualTo(apptA1Booked.toString());

    // S12: Patient reads someone else's appointment -> 404 (hides existence) + denied audit
    var patOther = get("/fhir/Appointment/" + apptB1Fulfilled, patientAToken);
    assertThat(patOther.statusCode()).isEqualTo(404);
    assertThat(errorCode(patOther)).isEqualTo("not-found");

    // S12: Doctor reads own appointment -> 200; reads another doctor's appointment -> 404 + audit
    var docOwn = get("/fhir/Appointment/" + apptA1Booked, doctor1Token);
    assertThat(docOwn.statusCode()).isEqualTo(200);
    assertThat(docOwn.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"1\"");

    var docOther = get("/fhir/Appointment/" + apptB1Fulfilled, doctor1Token);
    assertThat(docOther.statusCode()).isEqualTo(404);
    assertThat(errorCode(docOther)).isEqualTo("not-found");

    // S12: Nurse reads any appointment -> 200
    assertThat(get("/fhir/Appointment/" + apptA1Booked, nurseToken).statusCode()).isEqualTo(200);
    var nurseB1 = get("/fhir/Appointment/" + apptB1Fulfilled, nurseToken);
    assertThat(nurseB1.statusCode()).isEqualTo(200);
    assertThat(nurseB1.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"3\"");

    // S12: Admin and DPO receive 403 forbidden
    var adminRead = get("/fhir/Appointment/" + apptA1Booked, adminToken);
    assertThat(adminRead.statusCode()).isEqualTo(403);
    assertThat(errorCode(adminRead)).isEqualTo("forbidden");

    var dpoRead = get("/fhir/Appointment/" + apptA1Booked, dpoToken);
    assertThat(dpoRead.statusCode()).isEqualTo(403);
    assertThat(errorCode(dpoRead)).isEqualTo("forbidden");

    // S12 & S13: Unauthenticated receives 401 unauthenticated
    assertThat(get("/fhir/Appointment/" + apptA1Booked, null).statusCode()).isEqualTo(401);
    assertThat(get("/fhir/Appointment?date=2026-12", null).statusCode()).isEqualTo(401);

    // S13: Admin and DPO receive 403 forbidden
    assertThat(get("/fhir/Appointment?patient=Patient/" + PATIENT_A, adminToken).statusCode())
        .isEqualTo(403);
    assertThat(get("/fhir/Appointment?patient=Patient/" + PATIENT_A, dpoToken).statusCode())
        .isEqualTo(403);

    // S12: Unknown or malformed UUID -> 404 for Patient/Doctor/Nurse, 403 for Admin
    UUID missingId = UUID.fromString("0192f8a4-9999-7000-8000-999999999999");
    assertThat(get("/fhir/Appointment/" + missingId, patientAToken).statusCode()).isEqualTo(404);
    assertThat(get("/fhir/Appointment/" + missingId, doctor1Token).statusCode()).isEqualTo(404);
    assertThat(get("/fhir/Appointment/" + missingId, nurseToken).statusCode()).isEqualTo(404);
    assertThat(get("/fhir/Appointment/not-a-uuid", nurseToken).statusCode()).isEqualTo(404);
    assertThat(get("/fhir/Appointment/not-a-uuid", adminToken).statusCode()).isEqualTo(403);

    // Verify denied audits were recorded for Patient(not-own), Doctor(not-self), and Admin/DPO
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          List<JsonNode> events = auditEvents();
          assertThat(events)
              .anySatisfy(evt -> {
                JsonNode data = evt.path("data");
                assertThat(data.path("outcome").asString()).isEqualTo("denied");
                assertThat(data.path("actor").path("role").asString()).isEqualTo("patient");
                assertThat(data.path("reason").asString()).isEqualTo("unmet-own");
                assertThat(data.path("patientId").asString()).isEqualTo(PATIENT_B.toString());
              });
          assertThat(events)
              .anySatisfy(evt -> {
                JsonNode data = evt.path("data");
                assertThat(data.path("outcome").asString()).isEqualTo("denied");
                assertThat(data.path("actor").path("role").asString()).isEqualTo("doctor");
                assertThat(data.path("reason").asString()).isEqualTo("unmet-self-practitioner");
              });
          assertThat(events)
              .anySatisfy(evt -> {
                JsonNode data = evt.path("data");
                assertThat(data.path("outcome").asString()).isEqualTo("denied");
                assertThat(data.path("actor").path("role").asString()).isEqualTo("admin");
              });
        });
  }

  @Test
  @DisplayName("TC-AC-MA.1: Patient searching without filter or with patient=B sees only A")
  void patientFilterIsAlwaysForcedToSelf() throws Exception {
    var noParams = get("/fhir/Appointment", patientAToken);
    assertThat(noParams.statusCode()).isEqualTo(200);
    assertThat(entryIds(noParams))
        .containsExactly(
            apptA1Booked.toString(),
            apptA2Arrived.toString(),
            apptA3Cancelled.toString(),
            apptA4WithDoc2.toString());

    var filteredToB = get("/fhir/Appointment?patient=Patient/" + PATIENT_B, patientAToken);
    assertThat(filteredToB.statusCode()).isEqualTo(200);
    assertThat(entryIds(filteredToB))
        .containsExactly(
            apptA1Booked.toString(),
            apptA2Arrived.toString(),
            apptA3Cancelled.toString(),
            apptA4WithDoc2.toString())
        .doesNotContain(
            apptB1Fulfilled.toString(),
            apptB2SameTimeLow.toString(),
            apptB3SameTimeHigh.toString());
  }

  @Test
  @DisplayName("US-013: Doctor searching is forced to own practitioner and may narrow by patient")
  void doctorFilterIsAlwaysForcedToSelf() throws Exception {
    var noParams = get("/fhir/Appointment", doctor1Token);
    assertThat(noParams.statusCode()).isEqualTo(200);
    assertThat(entryIds(noParams))
        .containsExactly(
            apptA1Booked.toString(),
            apptB2SameTimeLow.toString(),
            apptB3SameTimeHigh.toString(),
            apptA2Arrived.toString(),
            apptA3Cancelled.toString());

    var overridePractitioner =
        get("/fhir/Appointment?practitioner=Practitioner/" + DOCTOR_2, doctor1Token);
    assertThat(overridePractitioner.statusCode()).isEqualTo(200);
    assertThat(entryIds(overridePractitioner))
        .containsExactly(
            apptA1Booked.toString(),
            apptB2SameTimeLow.toString(),
            apptB3SameTimeHigh.toString(),
            apptA2Arrived.toString(),
            apptA3Cancelled.toString())
        .doesNotContain(apptA4WithDoc2.toString(), apptB1Fulfilled.toString());

    var doc1ForPatientA = get("/fhir/Appointment?patient=Patient/" + PATIENT_A, doctor1Token);
    assertThat(doc1ForPatientA.statusCode()).isEqualTo(200);
    assertThat(entryIds(doc1ForPatientA))
        .containsExactly(
            apptA1Booked.toString(), apptA2Arrived.toString(), apptA3Cancelled.toString());
  }

  @Test
  @DisplayName("S13 Nurse searches filters, sorting (date and -date), and keyset pagination")
  void nurseFiltersSortingAndKeysetPagination() throws Exception {
    // Filter by status (comma-separated OR) and specialty
    var filtered =
        get(
            "/fhir/Appointment?practitioner=Practitioner/"
                + DOCTOR_1
                + "&status=arrived,cancelled&specialty="
                + URLEncoder.encode(
                    AppointmentSearchCriteria.SPECIALTY_SYSTEM + "|cardiology",
                    StandardCharsets.UTF_8),
            nurseToken);
    assertThat(filtered.statusCode()).isEqualTo(200);
    assertThat(entryIds(filtered))
        .containsExactly(apptA2Arrived.toString(), apptA3Cancelled.toString());

    // Ascending keyset pagination (_sort=date, default) across all 5 Doctor 1 appointments
    List<String> ascCollected = new ArrayList<>();
    String nextPath = "/fhir/Appointment?practitioner=Practitioner/" + DOCTOR_1 + "&_count=2";
    int pages = 0;
    while (nextPath != null) {
      pages++;
      var pageRes = get(nextPath, nurseToken);
      assertThat(pageRes.statusCode()).isEqualTo(200);
      assertThat(linkUrl(pageRes, "self")).isNotBlank();
      ascCollected.addAll(entryIds(pageRes));
      String nextFull = linkUrl(pageRes, "next");
      nextPath =
          nextFull == null
              ? null
              : URI.create(nextFull).getRawPath() + "?" + URI.create(nextFull).getRawQuery();
    }
    assertThat(pages).isEqualTo(3);
    assertThat(ascCollected)
        .containsExactly(
            apptA1Booked.toString(),
            apptB2SameTimeLow.toString(),
            apptB3SameTimeHigh.toString(),
            apptA2Arrived.toString(),
            apptA3Cancelled.toString());

    // Descending keyset pagination (_sort=-date) verifies start_at DESC, id ASC tie-breaking
    List<String> descCollected = new ArrayList<>();
    nextPath = "/fhir/Appointment?practitioner=Practitioner/" + DOCTOR_1 + "&_sort=-date&_count=2";
    pages = 0;
    while (nextPath != null) {
      pages++;
      var pageRes = get(nextPath, nurseToken);
      assertThat(pageRes.statusCode()).isEqualTo(200);
      descCollected.addAll(entryIds(pageRes));
      String nextFull = linkUrl(pageRes, "next");
      nextPath =
          nextFull == null
              ? null
              : URI.create(nextFull).getRawPath() + "?" + URI.create(nextFull).getRawQuery();
    }
    assertThat(pages).isEqualTo(3);
    assertThat(descCollected)
        .containsExactly(
            apptA3Cancelled.toString(),
            apptA2Arrived.toString(),
            apptB2SameTimeLow.toString(),
            apptB3SameTimeHigh.toString(),
            apptA1Booked.toString());

    // Cross-sort page token replay must fail with 400
    var page1Asc =
        get("/fhir/Appointment?practitioner=Practitioner/" + DOCTOR_1 + "&_count=2", nurseToken);
    URI ascNext = URI.create(linkUrl(page1Asc, "next"));
    String ascToken = extractQueryParam(ascNext.getRawQuery(), "_page_token");
    var crossSort =
        get(
            "/fhir/Appointment?practitioner=Practitioner/"
                + DOCTOR_1
                + "&_sort=-date&_count=2&_page_token="
                + URLEncoder.encode(ascToken, StandardCharsets.UTF_8),
            nurseToken);
    assertThat(crossSort.statusCode()).isEqualTo(400);
    assertThat(errorCode(crossSort)).isEqualTo("validation-failed");

    // Patient A token cannot be replayed by Nurse for Patient B (different effective patient)
    var patPage1 = get("/fhir/Appointment?_count=2", patientAToken);
    URI patNext = URI.create(linkUrl(patPage1, "next"));
    String patToken = extractQueryParam(patNext.getRawQuery(), "_page_token");
    var replayedForB =
        get(
            "/fhir/Appointment?patient=Patient/"
                + PATIENT_B
                + "&_count=2&_page_token="
                + URLEncoder.encode(patToken, StandardCharsets.UTF_8),
            nurseToken);
    assertThat(replayedForB.statusCode()).isEqualTo(400);
    assertThat(errorCode(replayedForB)).isEqualTo("validation-failed");
  }

  @Test
  @DisplayName("S13 validates required filters, 92-day date limit, and search parameters")
  void validationAndUnsupportedParameterErrors() throws Exception {
    // Nurse without any of patient, practitioner, date -> 400
    var missingFilter = get("/fhir/Appointment", nurseToken);
    assertThat(missingFilter.statusCode()).isEqualTo(400);
    assertThat(errorCode(missingFilter)).isEqualTo("validation-failed");

    // Nurse with unbounded date-only search -> 400
    assertThat(get("/fhir/Appointment?date=ge2026-12-01", nurseToken).statusCode()).isEqualTo(400);

    // Date range = exactly 92 days succeeds
    var exact92 =
        get(
            "/fhir/Appointment?date=ge2026-11-01T00:00:00Z&date=lt2027-02-01T00:00:00Z",
            nurseToken);
    assertThat(exact92.statusCode()).isEqualTo(200);
    assertThat(entryIds(exact92)).hasSize(7);

    // Date range > 92 days (93 days) and whole year -> 400
    var over92 =
        get(
            "/fhir/Appointment?date=ge2026-11-01T00:00:00Z&date=lt2027-02-02T00:00:00Z",
            nurseToken);
    assertThat(over92.statusCode()).isEqualTo(400);
    assertThat(errorCode(over92)).isEqualTo("validation-failed");
    assertThat(get("/fhir/Appointment?date=2026", nurseToken).statusCode()).isEqualTo(400);

    // Inverted date range -> 400
    assertThat(
            get("/fhir/Appointment?date=ge2026-12-10&date=lt2026-12-01", nurseToken).statusCode())
        .isEqualTo(400);

    // Invalid parameter values -> 400 validation-failed
    assertThat(
            get("/fhir/Appointment?patient=Patient/" + PATIENT_A + "&status=pending", nurseToken)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            get("/fhir/Appointment?patient=Patient/" + PATIENT_A + "&specialty=invalid", nurseToken)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            get(
                    "/fhir/Appointment?patient=Patient/"
                        + PATIENT_A
                        + "&specialty="
                        + URLEncoder.encode(
                            "http://wrong.example/system|cardiology", StandardCharsets.UTF_8),
                    nurseToken)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            get("/fhir/Appointment?patient=Patient/" + PATIENT_A + "&_sort=status", nurseToken)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            get(
                    "/fhir/Appointment?patient=Patient/" + PATIENT_A + "&_sort=date&_sort=-date",
                    nurseToken)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            get("/fhir/Appointment?patient=Patient/" + PATIENT_A + "&_count=101", nurseToken)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            get("/fhir/Appointment?patient=Patient/" + PATIENT_A + "&_count=0", nurseToken)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            get(
                    "/fhir/Appointment?patient=Patient/"
                        + PATIENT_A
                        + "&_page_token=tampered.token",
                    nurseToken)
                .statusCode())
        .isEqualTo(400);
    assertThat(get("/fhir/Appointment?patient=Patient/not-a-uuid", nurseToken).statusCode())
        .isEqualTo(400);

    // Unknown parameter -> 400 unsupported-parameter
    var unknown = get("/fhir/Appointment?patient=Patient/" + PATIENT_A + "&foo=bar", nurseToken);
    assertThat(unknown.statusCode()).isEqualTo(400);
    assertThat(errorCode(unknown)).isEqualTo("unsupported-parameter");
  }

  @Test
  @DisplayName("AppointmentSearchCriteria enforces domain invariants")
  void criteriaEnforcesDomainInvariants() {
    assertThatThrownBy(() ->
            new AppointmentSearchCriteria(
                PATIENT_A, null, Set.of("unknown"), null, null, null, false, null, null, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new AppointmentSearchCriteria(
                null, null, Set.of(), null, null, null, false, null, null, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new AppointmentSearchCriteria(
                PATIENT_A, null, Set.of(), null, null, null, false, T_DAY1_08, null, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new AppointmentSearchCriteria(
                PATIENT_A, null, Set.of(), null, null, null, false, null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String extractQueryParam(String rawQuery, String name) {
    for (String pair : rawQuery.split("&")) {
      String[] kv = pair.split("=", 2);
      if (kv[0].equals(name) && kv.length == 2) {
        return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
      }
    }
    throw new IllegalStateException("Missing param " + name + " in " + rawQuery);
  }
}
