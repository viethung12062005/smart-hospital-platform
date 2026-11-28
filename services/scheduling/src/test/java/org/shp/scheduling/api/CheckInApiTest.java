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
import java.time.temporal.ChronoUnit;
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
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.BookingTestSupport;
import org.shp.scheduling.domain.AppointmentFhir;
import org.shp.scheduling.domain.AppointmentRecord;
import org.shp.scheduling.infra.AppointmentRepository;
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
 * SCH-20 (US-021): S16 {@code POST /fhir/Appointment/{id}/$check-in} end-to-end integration tests
 * on real PostgreSQL 16 and the real auth, precondition, authorization, audit, and outbox pipeline.
 * Synthetic IDs and subjects only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + CheckInApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + CheckInApiTest.TestSecurityConfig.AUDIENCE,
      "shp.events.validate-schema=true"
    })
@Testcontainers
@Import(CheckInApiTest.TestSecurityConfig.class)
class CheckInApiTest {
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
  private static final UUID PATIENT_A = UUID.fromString("0192f8a4-0000-7000-8000-00000000001a");
  private static final UUID DOCTOR_A = UUID.fromString("0192f8a4-0000-7000-8000-00000000001d");

  @Autowired Environment env;
  @Autowired TestSecurityConfig.FakeIdentityLinkClient identityLinks;
  @Autowired AppointmentRepository appointments;
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

  private BookingTestSupport data;
  private UUID schedule;
  private String nurseToken;

  @BeforeEach
  void seed() {
    data = new BookingTestSupport(jdbc);
    data.clean();
    identityLinks.links.clear();
    schedule = data.schedule(DOCTOR_A, "cardiology");
    identityLinks.links.put(
        "nurse-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    identityLinks.links.put("patient-a", new IdentityLink("Patient", PATIENT_A.toString(), false));
    identityLinks.links.put(
        "doctor-a", new IdentityLink("Practitioner", DOCTOR_A.toString(), false));
    identityLinks.links.put(
        "admin-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    identityLinks.links.put(
        "dpo-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    nurseToken = token("nurse-1", List.of("nurse"));
  }

  private AppointmentRecord seedBookedAppointment(Instant start) {
    UUID slotId = data.slot(schedule, DOCTOR_A, "cardiology", "busy", start);
    UUID apptId = UUID.randomUUID();
    Instant createdAt = Instant.now().minus(1, ChronoUnit.DAYS);
    AppointmentRecord draft =
        new AppointmentRecord(
            apptId,
            1,
            createdAt,
            "booked",
            slotId,
            PATIENT_A,
            DOCTOR_A,
            "cardiology",
            start,
            start.plusSeconds(900),
            "web",
            "patient-a",
            "{}");
    AppointmentRecord booked = draft.withContent(AppointmentFhir.booked(draft, null));
    String stored = appointments.insertWithHistory(booked);
    return booked.withContent(stored);
  }

  @Test
  @DisplayName(
      "S16: Nurse checks in a booked appointment within window -> 200, ETag, history, E4 outbox & audit")
  void nurseChecksInBookedAppointmentWithinWindow() throws Exception {
    AppointmentRecord appt =
        seedBookedAppointment(
            Instant.now().plus(15, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS));

    HttpResponse<String> res = postCheckIn(appt.id().toString(), nurseToken, "W/\"1\"");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"2\"");
    assertThat(res.headers().firstValue(HttpHeaders.CONTENT_TYPE))
        .hasValueSatisfying(ct -> assertThat(ct).startsWith(FhirJson.MEDIA_TYPE));

    JsonNode body = JSON.readTree(res.body());
    assertThat(body.path("resourceType").asString()).isEqualTo("Appointment");
    assertThat(body.path("id").asString()).isEqualTo(appt.id().toString());
    assertThat(body.path("status").asString()).isEqualTo("arrived");
    assertThat(body.path("meta").path("versionId").asString()).isEqualTo("2");

    assertThat(
            jdbc.sql("SELECT status || ':' || version_id FROM scheduling.appointment WHERE id = ?")
                .param(appt.id())
                .query(String.class)
                .single())
        .isEqualTo("arrived:2");
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM scheduling.resource_history WHERE resource_type = 'Appointment' AND id = ? AND version_id = 2 AND operation = 'update' AND changed_by = 'nurse-1'")
                .param(appt.id())
                .query(Long.class)
                .single())
        .isEqualTo(1);

    String checkedInPayload =
        jdbc.sql("SELECT payload::text FROM scheduling.outbox WHERE event_type = ?")
            .param("shp.scheduling.appointment-checked-in.v1")
            .query(String.class)
            .single();
    CloudEvent checkedInEvent = CloudEvents.parse(checkedInPayload);
    assertThat(EventSchemas.validate(checkedInEvent)).isEmpty();
    assertThat(checkedInEvent.subject()).isEqualTo("Appointment/" + appt.id());
    assertThat(checkedInEvent.data().get("appointmentId").asString())
        .isEqualTo(appt.id().toString());
    assertThat(checkedInEvent.data().get("version").asInt()).isEqualTo(2);
    assertThat(checkedInEvent.data().get("patientId").asString()).isEqualTo(PATIENT_A.toString());
    assertThat(checkedInEvent.data().get("practitionerId").asString())
        .isEqualTo(DOCTOR_A.toString());
    assertThat(checkedInEvent.data().get("specialty").asString()).isEqualTo("cardiology");

    JsonNode auditPayload =
        JSON.readTree(
            jdbc.sql(
                    "SELECT payload::text FROM scheduling.outbox WHERE event_type = 'shp.audit.recorded.v1'")
                .query(String.class)
                .single());
    JsonNode auditData = auditPayload.path("data");
    assertThat(auditData.path("action").asString()).isEqualTo("update");
    assertThat(auditData.path("outcome").asString()).isEqualTo("success");
    assertThat(auditData.path("resource").asString()).isEqualTo("Appointment/" + appt.id());
    assertThat(auditData.path("patientId").asString()).isEqualTo(PATIENT_A.toString());
  }

  @Test
  @DisplayName("S16 / TC-AC-CI.1: Checking in an appointment tomorrow or >30m late returns 422")
  void checkingInTomorrowAppointmentReturns422() throws Exception {
    AppointmentRecord tomorrow = seedBookedAppointment(Instant.now().plus(1, ChronoUnit.DAYS));
    AppointmentRecord tooLate = seedBookedAppointment(Instant.now().minus(31, ChronoUnit.MINUTES));

    for (AppointmentRecord appt : List.of(tomorrow, tooLate)) {
      HttpResponse<String> res = postCheckIn(appt.id().toString(), nurseToken, "W/\"1\"");

      assertThat(res.statusCode()).isEqualTo(422);
      JsonNode outcome = JSON.readTree(res.body());
      assertThat(outcome.path("resourceType").asString()).isEqualTo("OperationOutcome");
      assertThat(
              outcome
                  .path("issue")
                  .get(0)
                  .path("details")
                  .path("coding")
                  .get(0)
                  .path("code")
                  .asString())
          .isEqualTo("checkin-out-of-window");
      assertThat(
              jdbc.sql("SELECT status FROM scheduling.appointment WHERE id = ?")
                  .param(appt.id())
                  .query(String.class)
                  .single())
          .isEqualTo("booked");
    }

    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM scheduling.outbox WHERE event_type = 'shp.scheduling.appointment-checked-in.v1'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("S16: Non-Nurse roles (patient, doctor, admin, dpo) get 403 and denied audit")
  void nonNurseRolesAreDeniedWith403AndAudited() throws Exception {
    AppointmentRecord appt = seedBookedAppointment(Instant.now().plus(10, ChronoUnit.MINUTES));

    HttpResponse<String> unauthenticated = postCheckIn(appt.id().toString(), null, "W/\"1\"");
    assertThat(unauthenticated.statusCode()).isEqualTo(401);

    List<String> forbiddenTokens =
        List.of(
            token("patient-a", List.of("patient")),
            token("doctor-a", List.of("doctor")),
            token("admin-1", List.of("admin")),
            token("dpo-1", List.of("dpo")));

    for (String jwt : forbiddenTokens) {
      HttpResponse<String> res = postCheckIn(appt.id().toString(), jwt, "W/\"1\"");
      assertThat(res.statusCode()).isEqualTo(403);
    }

    assertThat(
            jdbc.sql("SELECT status FROM scheduling.appointment WHERE id = ?")
                .param(appt.id())
                .query(String.class)
                .single())
        .isEqualTo("booked");
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM scheduling.outbox WHERE event_type = 'shp.scheduling.appointment-checked-in.v1'")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM scheduling.outbox WHERE event_type = 'shp.audit.recorded.v1' AND payload->'data'->>'outcome' = 'denied'")
                .query(Long.class)
                .single())
        .isEqualTo(4);
  }

  @Test
  @DisplayName(
      "S16: Missing If-Match -> 428, stale version -> 412, already arrived -> 409, unknown -> 404")
  void missingIfMatchIs428StaleVersionIs412AlreadyArrivedIs409UnknownIs404() throws Exception {
    AppointmentRecord appt = seedBookedAppointment(Instant.now().plus(10, ChronoUnit.MINUTES));

    HttpResponse<String> missingIfMatch = postCheckIn(appt.id().toString(), nurseToken, null);
    assertThat(missingIfMatch.statusCode()).isEqualTo(428);

    HttpResponse<String> staleVersion = postCheckIn(appt.id().toString(), nurseToken, "W/\"99\"");
    assertThat(staleVersion.statusCode()).isEqualTo(412);

    HttpResponse<String> first = postCheckIn(appt.id().toString(), nurseToken, "W/\"1\"");
    assertThat(first.statusCode()).isEqualTo(200);

    HttpResponse<String> second = postCheckIn(appt.id().toString(), nurseToken, "W/\"2\"");
    assertThat(second.statusCode()).isEqualTo(409);

    HttpResponse<String> unknown = postCheckIn(UUID.randomUUID().toString(), nurseToken, "W/\"1\"");
    assertThat(unknown.statusCode()).isEqualTo(404);

    HttpResponse<String> malformedId = postCheckIn("not-a-uuid", nurseToken, "W/\"1\"");
    assertThat(malformedId.statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> postCheckIn(String id, String jwt, String ifMatch) throws Exception {
    var builder =
        HttpRequest.newBuilder(url("/fhir/Appointment/" + id + "/$check-in"))
            .POST(HttpRequest.BodyPublishers.noBody());
    if (jwt != null) {
      builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
    }
    if (ifMatch != null) {
      builder.header(HttpHeaders.IF_MATCH, ifMatch);
    }
    try (var http = HttpClient.newHttpClient()) {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private URI url(String path) {
    return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
  }

  private static String token(String subject, List<String> roles) {
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
}
