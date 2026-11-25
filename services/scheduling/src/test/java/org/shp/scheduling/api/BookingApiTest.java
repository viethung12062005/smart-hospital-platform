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
import org.shp.authz.LinkedProfile;
import org.shp.scheduling.app.BookingTestSupport;
import org.shp.scheduling.infra.FakePatientClient;
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
 * SCH-14 (US-015): S11 {@code POST /fhir/Appointment/$book} end to end on real PostgreSQL 16 and
 * the real authorization, identity and idempotency filters. Synthetic ids and subjects only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + BookingApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + BookingApiTest.TestSecurityConfig.AUDIENCE,
      "shp.events.validate-schema=true"
    })
@Testcontainers
@Import(BookingApiTest.TestSecurityConfig.class)
class BookingApiTest {
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
  @Autowired FakePatientClient patients;
  @Autowired Clock clock;

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
    FakePatientClient fakePatientClient() {
      return new FakePatientClient();
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
  private static final UUID CHILD = UUID.fromString("0192f8a4-0000-7000-8000-00000000000c");
  private static final UUID EXPIRED_CHILD = UUID.fromString("0192f8a4-0000-7000-8000-00000000000d");
  private String patientAToken;
  private String nurseToken;

  @BeforeEach
  void seed() {
    data = new BookingTestSupport(jdbc);
    data.clean();
    identityLinks.links.clear();
    patients.clear();
    doctor = UUID.randomUUID();
    schedule = data.schedule(doctor, "cardiology");
    counter = 0;
    identityLinks.links.put("patient-a", new IdentityLink("Patient", PATIENT_A.toString(), false));
    identityLinks.links.put("patient-b", new IdentityLink("Patient", PATIENT_B.toString(), false));
    identityLinks.links.put(
        "guardian-1",
        new IdentityLink(
            "Patient",
            PATIENT_A.toString(),
            false,
            List.of(
                new LinkedProfile(CHILD.toString(), "guardian", "booking", null),
                new LinkedProfile(
                    EXPIRED_CHILD.toString(),
                    "proxy",
                    "booking",
                    clock.instant().minusSeconds(3600)))));
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

  @Test
  @DisplayName("201: a patient books a free slot for themselves with the default channel web")
  void patientBooksForSelf() throws Exception {
    UUID slot = freeSlot(3);
    var res = book(patientAToken, "key-api-0001", params(slot, null, null, "Chest pain"));

    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(res.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"1\"");
    JsonNode a = JSON.readTree(res.body());
    assertThat(res.headers().firstValue(HttpHeaders.LOCATION).orElse(""))
        .isEqualTo("/fhir/Appointment/" + a.path("id").asString() + "/_history/1");
    assertThat(a.path("resourceType").asString()).isEqualTo("Appointment");
    assertThat(a.path("status").asString()).isEqualTo("booked");
    assertThat(a.path("comment").asString()).isEqualTo("Chest pain");
    assertThat(a.path("participant").path(0).path("actor").path("reference").asString())
        .isEqualTo("Patient/" + PATIENT_A);
    assertThat(a.path("extension").path(0).path("valueCode").asString()).isEqualTo("web");
    assertThat(count("SELECT count(*) FROM scheduling.slot WHERE status = 'busy'")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM outbox WHERE event_type = 'shp.scheduling.appointment-booked.v1'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("AC-03.2: the same key and body returns 200 with the same appointment")
  void replayReturnsTheSameAppointment() throws Exception {
    String body = params(freeSlot(3), null, null, null);
    var first = book(patientAToken, "key-api-0002", body);
    var again = book(patientAToken, "key-api-0002", body);

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(again.body()).path("id").asString())
        .isEqualTo(JSON.readTree(first.body()).path("id").asString());
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM outbox WHERE event_type = 'shp.scheduling.appointment-booked.v1'"))
        .isEqualTo(1);
  }

  @Test
  void replayReturnsVersionOneAfterTheAppointmentChanges() throws Exception {
    String body = params(freeSlot(3), null, null, null);
    String key = "key-api-edit";
    var created = book(patientAToken, key, body);
    assertThat(created.statusCode()).isEqualTo(201);

    jdbc.sql(
            """
            UPDATE scheduling.appointment SET version_id = 2, status = 'cancelled',
              cancel_reason = 'patient-request',
              content = jsonb_set(jsonb_set(content, '{meta,versionId}', '"2"'), '{status}', '"cancelled"')
            WHERE id = ?
            """)
        .param(UUID.fromString(JSON.readTree(created.body()).path("id").asString()))
        .update();

    var replay = book(patientAToken, key, body);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.body()).isEqualTo(created.body());
    assertThat(replay.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"1\"");
    assertThat(replay.headers().firstValue(HttpHeaders.LOCATION))
        .isEqualTo(created.headers().firstValue(HttpHeaders.LOCATION));
  }

  @Test
  @DisplayName("AC-03.3: the same key with another body is 422 idempotency-key-reused")
  void sameKeyDifferentBodyIsRejected() throws Exception {
    assertThat(
            book(patientAToken, "key-api-0003", params(freeSlot(3), null, null, null)).statusCode())
        .isEqualTo(201);
    var res = book(patientAToken, "key-api-0003", params(freeSlot(4), null, null, null));

    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(errorCode(res)).isEqualTo("idempotency-key-reused");
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
  }

  @Test
  @DisplayName("428 without Idempotency-Key, 400 when it is malformed, 401 without a token")
  void headerAndTokenChecks() throws Exception {
    String body = params(freeSlot(3), null, null, null);
    var missing = book(patientAToken, null, body);
    assertThat(missing.statusCode()).isEqualTo(428);
    assertThat(errorCode(missing)).isEqualTo("idempotency-key-required");
    assertThat(book(patientAToken, "short", body).statusCode()).isEqualTo(400);
    assertThat(book(null, "key-api-0004", body).statusCode()).isEqualTo(401);
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isZero();
  }

  @Test
  @DisplayName("400 for a body that is not a valid Parameters resource")
  void invalidBodiesAreRejected() throws Exception {
    UUID slot = freeSlot(3);
    List<String> bad =
        List.of(
            "not json",
            "{\"resourceType\":\"Patient\"}",
            "{\"resourceType\":\"Parameters\",\"parameter\":[]}",
            "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"slot\",\"valueReference\":{\"reference\":\"Slot/not-a-uuid\"}}]}",
            "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"slot\",\"valueString\":\"x\"}]}",
            "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"other\",\"valueString\":\"x\"}]}",
            params(slot, null, null, null)
                .replace(
                    "\"valueReference\"",
                    "\"part\":[{\"name\":\"extra\",\"valueString\":\"x\"}],\"valueReference\""),
            params(slot, null, "carrier-pigeon", null),
            params(slot, null, null, "x".repeat(501)),
            params(slot, null, "staff", null));
    int i = 0;
    for (String body : bad) {
      var res = book(patientAToken, "key-bad-%04d".formatted(i++), body);
      assertThat(res.statusCode()).as(body).isEqualTo(400);
      assertThat(errorCode(res)).isEqualTo("validation-failed");
    }
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isZero();
  }

  @Test
  @DisplayName("AC-03.6: booking for another patient is 403 and the denial is audited")
  void bookingForSomeoneElseIsDeniedAndAudited() throws Exception {
    UUID slot = freeSlot(3);
    var res = book(patientAToken, "key-api-0005", params(slot, PATIENT_B, null, null));

    assertThat(res.statusCode()).isEqualTo(403);
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isZero();
    assertThat(count("SELECT count(*) FROM scheduling.slot WHERE status = 'free'")).isEqualTo(1);
    var audits =
        jdbc.sql(
                """
                SELECT payload::text FROM outbox
                 WHERE event_type = 'shp.audit.recorded.v1'
                   AND payload->'data'->>'outcome' = 'denied'
                   AND payload->'data'->'actor'->>'subject' = 'patient-a'
                """)
            .query(String.class)
            .list();
    assertThat(audits).hasSize(1);
    JsonNode audit = JSON.readTree(audits.getFirst()).path("data");
    // the audit vocabulary records a booking as a create of an Appointment
    assertThat(audit.path("action").asString()).isEqualTo("create");
    assertThat(audit.path("resource").asString()).startsWith("Appointment");
  }

  @Test
  @DisplayName("a patient naming their own profile books; doctor and admin get 403")
  void ownProfileBooksAndOtherRolesAreDenied() throws Exception {
    assertThat(
            book(patientAToken, "key-api-0006", params(freeSlot(3), PATIENT_A, "chatbot", null))
                .statusCode())
        .isEqualTo(201);
    assertThat(
            book(
                    token("doctor-1", List.of("doctor")),
                    "key-api-0007",
                    params(freeSlot(4), PATIENT_A, null, null))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            book(
                    token("admin-1", List.of("admin")),
                    "key-api-0008",
                    params(freeSlot(5), null, null, null))
                .statusCode())
        .isEqualTo(403);
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
  }

  @Test
  @DisplayName("a nurse books for any patient and the channel is forced to staff")
  void nurseBooksForAPatientWithChannelStaff() throws Exception {
    var res = book(nurseToken, "key-api-0009", params(freeSlot(3), PATIENT_B, "web", null));

    assertThat(res.statusCode()).isEqualTo(201);
    JsonNode a = JSON.readTree(res.body());
    assertThat(a.path("participant").path(0).path("actor").path("reference").asString())
        .isEqualTo("Patient/" + PATIENT_B);
    assertThat(a.path("extension").path(0).path("valueCode").asString()).isEqualTo("staff");
    assertThat(jdbc.sql("SELECT channel FROM scheduling.appointment").query(String.class).single())
        .isEqualTo("staff");
    // A nurse must say who the patient is.
    assertThat(book(nurseToken, "key-api-0010", params(freeSlot(4), null, null, null)).statusCode())
        .isEqualTo(400);
  }

  @Test
  @DisplayName("404 for a patient that does not exist, and no slot is taken")
  void unknownPatientIsNotFound() throws Exception {
    UUID ghost = UUID.fromString("0192f8a4-0000-7000-8000-0000000000ee");
    patients.markMissing(ghost);
    UUID slot = freeSlot(3);

    var res = book(nurseToken, "key-api-0016", params(slot, ghost, null, null));

    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(errorCode(res)).isEqualTo("not-found");
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isZero();
    assertThat(count("SELECT count(*) FROM scheduling.slot WHERE status = 'free'")).isEqualTo(1);
  }

  @Test
  @DisplayName("a patient naming an unknown, unlinked id gets 403, never 404 (no existence oracle)")
  void unknownUnlinkedPatientIsForbiddenNotNotFound() throws Exception {
    UUID ghost = UUID.fromString("0192f8a4-0000-7000-8000-0000000000ef");
    patients.markMissing(ghost);

    var res = book(patientAToken, "key-api-0020", params(freeSlot(3), ghost, null, null));

    assertThat(res.statusCode()).isEqualTo(403);
    assertThat(patients.lookups()).isZero();
  }

  @Test
  @DisplayName("AC-03.2: a retry with the same key still gets 200 while clinical is down")
  void replayDoesNotNeedClinical() throws Exception {
    String body = params(freeSlot(3), null, null, null);
    var first = book(patientAToken, "key-api-0021", body);
    assertThat(first.statusCode()).isEqualTo(201);

    patients.setDown(true);
    var replay = book(patientAToken, "key-api-0021", body);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.body()).isEqualTo(first.body());

    var fresh = book(patientAToken, "key-api-0022", params(freeSlot(4), null, null, null));
    assertThat(fresh.statusCode()).isEqualTo(503);
    assertThat(errorCode(fresh)).isEqualTo("dependency-unavailable");
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
  }

  @Test
  @DisplayName("AC-03.6: a guardian books for a linked profile, an unlinked or expired one is 403")
  void linkedProfileBooking() throws Exception {
    String guardian = token("guardian-1", List.of("patient"));
    var linked = book(guardian, "key-api-0017", params(freeSlot(3), CHILD, null, null));
    assertThat(linked.statusCode()).isEqualTo(201);
    assertThat(
            JSON.readTree(linked.body())
                .path("participant")
                .path(0)
                .path("actor")
                .path("reference")
                .asString())
        .isEqualTo("Patient/" + CHILD);

    assertThat(
            book(guardian, "key-api-0018", params(freeSlot(4), EXPIRED_CHILD, null, null))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            book(guardian, "key-api-0019", params(freeSlot(5), PATIENT_B, null, null)).statusCode())
        .isEqualTo(403);
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
  }

  @Test
  @DisplayName("404 for an unknown slot")
  void unknownSlotIsNotFound() throws Exception {
    var res = book(patientAToken, "key-api-0011", params(UUID.randomUUID(), null, null, null));
    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(errorCode(res)).isEqualTo("not-found");
  }

  @Test
  @DisplayName("409 slot-unavailable lists the 3 nearest free slots, the same doctor first")
  void takenSlotIsAConflictWithSuggestions() throws Exception {
    UUID taken = freeSlot(3);
    assertThat(book(nurseToken, "key-api-0012", params(taken, PATIENT_B, null, null)).statusCode())
        .isEqualTo(201);
    UUID later = freeSlot(6);
    UUID soon = freeSlot(4);
    UUID soonest = freeSlot(3);
    UUID evenLater = freeSlot(7);
    UUID otherDoctor = UUID.randomUUID();
    UUID otherSchedule = data.schedule(otherDoctor, "cardiology");
    UUID otherDoctorSlot = data.slot(otherSchedule, otherDoctor, "cardiology", "free", start(1));

    var res = book(patientAToken, "key-api-0013", params(taken, null, null, null));

    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(errorCode(res)).isEqualTo("slot-unavailable");
    JsonNode outcome = JSON.readTree(res.body());
    assertThat(outcome.path("resourceType").asString()).isEqualTo("OperationOutcome");
    JsonNode ext = outcome.path("extension").path(0);
    assertThat(ext.path("url").asString()).isEqualTo(BookingController.SUGGESTED_SLOTS_URL);
    List<String> ids =
        ext.path("extension")
            .valueStream()
            .map(s ->
                s.path("extension").path(0).path("valueReference").path("reference").asString())
            .toList();
    assertThat(ids).containsExactly("Slot/" + soonest, "Slot/" + soon, "Slot/" + later);
    assertThat(ids).doesNotContain("Slot/" + otherDoctorSlot, "Slot/" + evenLater);
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
  }

  @Test
  void conflictWithoutAlternativesDoesNotEmitAnEmptyFhirExtension() throws Exception {
    UUID taken = freeSlot(3);
    assertThat(book(nurseToken, "key-api-taken", params(taken, PATIENT_B, null, null)).statusCode())
        .isEqualTo(201);

    var res = book(patientAToken, "key-api-no-alt", params(taken, null, null, null));
    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(errorCode(res)).isEqualTo("slot-unavailable");
    assertThat(JSON.readTree(res.body()).has("extension")).isFalse();
  }

  @Test
  @DisplayName("AC-03.4: the 4th future appointment is 422 max-future-appointments")
  void fourthFutureAppointmentIsRejected() throws Exception {
    for (int i = 0; i < 3; i++) {
      assertThat(
              book(
                      patientAToken,
                      "key-limit-%03d".formatted(i),
                      params(freeSlot(2 + i), null, null, null))
                  .statusCode())
          .isEqualTo(201);
    }
    UUID fourth = freeSlot(8);
    var res = book(patientAToken, "key-limit-999", params(fourth, null, null, null));

    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(errorCode(res)).isEqualTo("max-future-appointments");
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.slot WHERE id = '%s' AND status = 'free'"
                    .formatted(fourth)))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("BR-04: a slot more than 30 days ahead is 422 outside-booking-window")
  void slotBeyondTheWindowIsRejected() throws Exception {
    var res = book(patientAToken, "key-api-0014", params(freeSlot(31), null, null, null));
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(errorCode(res)).isEqualTo("outside-booking-window");
  }

  @Test
  @DisplayName("application/json is accepted as well as application/fhir+json")
  void plainJsonContentTypeWorks() throws Exception {
    var req =
        HttpRequest.newBuilder(url("/fhir/Appointment/$book"))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + patientAToken)
            .header("Idempotency-Key", "key-api-0015")
            .POST(HttpRequest.BodyPublishers.ofString(params(freeSlot(3), null, null, null)))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      assertThat(client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(201);
    }
  }
}
