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
import org.shp.scheduling.app.BookingTestSupport;
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
 * SCH-18 (US-016, US-019): S14 {@code POST /fhir/Appointment/{id}/$cancel} end to end on real
 * PostgreSQL 16 and the real authorization, identity and idempotency filters. Synthetic ids and
 * subjects only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + CancelApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + CancelApiTest.TestSecurityConfig.AUDIENCE,
      "shp.events.validate-schema=true"
    })
@Testcontainers
@Import(CancelApiTest.TestSecurityConfig.class)
class CancelApiTest {
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
  private UUID doctor;
  private UUID schedule;
  private int counter;

  // Fixed ids: the identity resolver caches links by subject for the life of the context.
  private static final UUID PATIENT_A = UUID.fromString("0192f8a4-0000-7000-8000-00000000000a");
  private static final UUID PATIENT_B = UUID.fromString("0192f8a4-0000-7000-8000-00000000000b");
  private String patientAToken;
  private String nurseToken;

  @BeforeEach
  void seed() {
    data = new BookingTestSupport(jdbc);
    data.clean();
    identityLinks.links.clear();
    doctor = UUID.randomUUID();
    schedule = data.schedule(doctor, "cardiology");
    counter = 0;
    identityLinks.links.put("patient-a", new IdentityLink("Patient", PATIENT_A.toString(), false));
    identityLinks.links.put("patient-b", new IdentityLink("Patient", PATIENT_B.toString(), false));
    identityLinks.links.put(
        "nurse-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    identityLinks.links.put("doctor-1", new IdentityLink("Practitioner", doctor.toString(), false));
    identityLinks.links.put(
        "admin-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    patientAToken = token("patient-a", List.of("patient"));
    nurseToken = token("nurse-1", List.of("nurse"));
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
      throw new IllegalStateException(e);
    }
  }

  private URI url(String path) {
    return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
  }

  private Instant start(long days) {
    return Instant.now()
        .plus(days, ChronoUnit.DAYS)
        .truncatedTo(ChronoUnit.SECONDS)
        .plusSeconds(900L * ++counter);
  }

  private UUID freeSlot(long days) {
    return data.slot(schedule, doctor, "cardiology", "free", start(days));
  }

  private static String params(UUID slot, UUID patient, String channel, String comment) {
    var sb = new StringBuilder("{\"resourceType\":\"Parameters\",\"parameter\":[");
    sb.append("{\"name\":\"slot\",\"valueReference\":{\"reference\":\"Slot/%s\"}}".formatted(slot));
    if (patient != null) {
      sb.append(
          ",{\"name\":\"patient\",\"valueReference\":{\"reference\":\"Patient/%s\"}}"
              .formatted(patient));
    }
    if (comment != null) {
      sb.append(",{\"name\":\"comment\",\"valueString\":\"%s\"}".formatted(comment));
    }
    if (channel != null) {
      sb.append(",{\"name\":\"channel\",\"valueCode\":\"%s\"}".formatted(channel));
    }
    return sb.append("]}").toString();
  }

  private HttpResponse<String> book(String bearer, String key, String body) throws Exception {
    var b =
        HttpRequest.newBuilder(url("/fhir/Appointment/$book"))
            .header(HttpHeaders.CONTENT_TYPE, "application/fhir+json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (bearer != null) {
      b.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    if (key != null) {
      b.header("Idempotency-Key", key);
    }
    try (var client = HttpClient.newHttpClient()) {
      return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private int count(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
  }

  private static String errorCode(HttpResponse<String> res) {
    return JSON.readTree(res.body())
        .path("issue")
        .path(0)
        .path("details")
        .path("coding")
        .path(0)
        .path("code")
        .asString();
  }

  private HttpResponse<String> cancel(
      String bearer, String key, UUID id, String ifMatch, String reason) throws Exception {
    var b =
        HttpRequest.newBuilder(url("/fhir/Appointment/" + id + "/$cancel"))
            .header(HttpHeaders.CONTENT_TYPE, "application/fhir+json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"reason\",\"valueCode\":\"%s\"}]}"
                        .formatted(reason)));
    if (bearer != null) {
      b.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    if (key != null) {
      b.header("Idempotency-Key", key);
    }
    if (ifMatch != null) {
      b.header(HttpHeaders.IF_MATCH, ifMatch);
    }
    try (var client = HttpClient.newHttpClient()) {
      return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  /**
   * Books a slot starting in {@code minutes} minutes for patient A and returns the appointment id.
   */
  private UUID bookedIn(long minutes) throws Exception {
    Instant at = Instant.now().plus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    UUID slot = data.slot(schedule, doctor, "cardiology", "free", at.plusSeconds(++counter));
    var res =
        book(patientAToken, "key-seed-%04d".formatted(counter), params(slot, null, null, null));
    assertThat(res.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(res.body()).path("id").asString());
  }

  private String slotStatus(UUID appointment) {
    return jdbc.sql(
            "SELECT s.status FROM scheduling.slot s JOIN scheduling.appointment a ON a.slot_id = s.id WHERE a.id = ?")
        .param(appointment)
        .query(String.class)
        .single();
  }

  @Test
  @DisplayName(
      "200: a patient cancels 3 hours ahead; the slot is free again and the event is queued")
  void patientCancelsEarlyEnough() throws Exception {
    UUID id = bookedIn(180);

    var res = cancel(patientAToken, "key-cancel-001", id, "W/\"1\"", "patient-request");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"2\"");
    JsonNode a = JSON.readTree(res.body());
    assertThat(a.path("status").asString()).isEqualTo("cancelled");
    assertThat(a.path("meta").path("versionId").asString()).isEqualTo("2");
    assertThat(a.path("cancelationReason").path("coding").path(0).path("code").asString())
        .isEqualTo("patient-request");
    assertThat(slotStatus(id)).isEqualTo("free");
    assertThat(count("SELECT count(*) FROM scheduling.resource_history WHERE version_id = 2"))
        .isEqualTo(1);
    var events =
        jdbc.sql(
                "SELECT payload::text FROM outbox WHERE event_type = 'shp.scheduling.appointment-cancelled.v1'")
            .query(String.class)
            .list();
    assertThat(events).hasSize(1);
    JsonNode data = JSON.readTree(events.getFirst()).path("data");
    assertThat(data.path("cancelledBy").asString()).isEqualTo("patient");
    assertThat(data.path("cancelReason").asString()).isEqualTo("patient-request");
    assertThat(data.path("version").asInt()).isEqualTo(2);
  }

  @Test
  @DisplayName("AC-03.5: a patient cancelling with under 2 hours left is 422, a nurse is allowed")
  void lateCancellation() throws Exception {
    UUID id = bookedIn(90);

    var late = cancel(patientAToken, "key-cancel-002", id, "W/\"1\"", "patient-request");
    assertThat(late.statusCode()).isEqualTo(422);
    assertThat(errorCode(late)).isEqualTo("cancellation-too-late");
    assertThat(slotStatus(id)).isEqualTo("busy");

    var staff = cancel(nurseToken, "key-cancel-003", id, "W/\"1\"", "other");
    assertThat(staff.statusCode()).isEqualTo(200);
    assertThat(
            JSON.readTree(
                    jdbc.sql(
                            "SELECT payload::text FROM outbox WHERE event_type = 'shp.scheduling.appointment-cancelled.v1'")
                        .query(String.class)
                        .single())
                .path("data")
                .path("cancelledBy")
                .asString())
        .isEqualTo("staff");
  }

  @Test
  @DisplayName(
      "no longer booked is 409 invalid-state-transition; a nurse may cancel arrived with other")
  void stateTransitions() throws Exception {
    UUID id = bookedIn(180);
    assertThat(
            cancel(patientAToken, "key-cancel-004", id, "W/\"1\"", "patient-request").statusCode())
        .isEqualTo(200);
    var again = cancel(patientAToken, "key-cancel-005", id, "W/\"2\"", "patient-request");
    assertThat(again.statusCode()).isEqualTo(409);
    assertThat(errorCode(again)).isEqualTo("invalid-state-transition");

    UUID arrived = bookedIn(240);
    jdbc.sql("UPDATE scheduling.appointment SET status = 'arrived' WHERE id = ?")
        .param(arrived)
        .update();
    assertThat(
            cancel(nurseToken, "key-cancel-006", arrived, "W/\"1\"", "patient-request")
                .statusCode())
        .isEqualTo(409);
    var ok = cancel(nurseToken, "key-cancel-007", arrived, "W/\"1\"", "other");
    assertThat(ok.statusCode()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "403 for another patient, 404 unknown id, 412 wrong version, 428 without If-Match or key")
  void errors() throws Exception {
    UUID id = bookedIn(180);
    String other = token("patient-b", List.of("patient"));
    assertThat(cancel(other, "key-cancel-008", id, "W/\"1\"", "patient-request").statusCode())
        .isEqualTo(403);
    assertThat(
            cancel(patientAToken, "key-cancel-009", UUID.randomUUID(), "W/\"1\"", "patient-request")
                .statusCode())
        .isEqualTo(404);
    var stale = cancel(patientAToken, "key-cancel-010", id, "W/\"7\"", "patient-request");
    assertThat(stale.statusCode()).isEqualTo(412);
    assertThat(errorCode(stale)).isEqualTo("version-conflict");
    assertThat(cancel(patientAToken, "key-cancel-011", id, null, "patient-request").statusCode())
        .isEqualTo(428);
    assertThat(cancel(patientAToken, null, id, "W/\"1\"", "patient-request").statusCode())
        .isEqualTo(428);
    assertThat(cancel(patientAToken, "key-cancel-012", id, "W/\"1\"", "not-a-reason").statusCode())
        .isEqualTo(400);
    assertThat(slotStatus(id)).isEqualTo("busy");
  }

  @Test
  @DisplayName("the same Idempotency-Key replays with 200 and emits one event")
  void replay() throws Exception {
    UUID id = bookedIn(180);
    assertThat(
            cancel(patientAToken, "key-cancel-013", id, "W/\"1\"", "patient-request").statusCode())
        .isEqualTo(200);
    assertThat(
            cancel(patientAToken, "key-cancel-013", id, "W/\"1\"", "patient-request").statusCode())
        .isEqualTo(200);
    assertThat(
            count(
                "SELECT count(*) FROM outbox WHERE event_type = 'shp.scheduling.appointment-cancelled.v1'"))
        .isEqualTo(1);
  }
}
