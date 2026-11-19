package org.shp.scheduling.api;

import static java.nio.charset.StandardCharsets.UTF_8;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * SCH-16 (US-015): AC-03.1 through the S11 {@code $book} endpoint on real PostgreSQL 16 - 50
 * concurrent requests for one free slot give exactly one appointment. Synthetic ids and subjects
 * only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + BookingConcurrencyApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + BookingConcurrencyApiTest.TestSecurityConfig.AUDIENCE,
      "shp.events.validate-schema=true"
    })
@Testcontainers
@Import(BookingConcurrencyApiTest.TestSecurityConfig.class)
class BookingConcurrencyApiTest {
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

  private record Outcome(int status, String code) {}

  @Test
  @DisplayName(
      "TC-AC-03.1: 50 patients book one free slot at once, exactly 1 gets 201 and 49 get 409")
  void fiftyConcurrentBookingsOfOneSlotCreateOneAppointment() throws Exception {
    int n = 50;
    UUID slot = freeSlot(3);
    List<String> tokens = new ArrayList<>();
    List<UUID> patients = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      UUID patient = UUID.nameUUIDFromBytes(("synthetic-patient-" + i).getBytes(UTF_8));
      String subject = "patient-race-" + i;
      identityLinks.links.put(subject, new IdentityLink("Patient", patient.toString(), false));
      tokens.add(token(subject, List.of("patient")));
      patients.add(patient);
    }

    var pool = Executors.newFixedThreadPool(n);
    var ready = new CountDownLatch(n);
    var go = new CountDownLatch(1);
    List<Future<Outcome>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      String bearer = tokens.get(i);
      String key = "key-race-%04d".formatted(i);
      String body = params(slot, null, "web", null);
      futures.add(
          pool.submit(() -> {
            ready.countDown();
            go.await();
            var res = book(bearer, key, body);
            return new Outcome(res.statusCode(), res.statusCode() == 201 ? null : errorCode(res));
          }));
    }
    ready.await();
    go.countDown();
    List<Outcome> results = new ArrayList<>();
    for (var f : futures) {
      results.add(f.get(120, TimeUnit.SECONDS));
    }
    pool.shutdown();

    assertThat(results).filteredOn(o -> o.status() == 201).hasSize(1);
    assertThat(results).filteredOn(o -> o.status() == 409).hasSize(n - 1);
    assertThat(results)
        .filteredOn(o -> o.status() == 409)
        .extracting(Outcome::code)
        .containsOnly("slot-unavailable");

    // No duplicate appointment: one appointment, one history row, one event, a busy slot.
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.appointment WHERE slot_id = '%s' AND status = 'booked'"
                    .formatted(slot)))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.resource_history WHERE resource_type = 'Appointment'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM outbox WHERE event_type = 'shp.scheduling.appointment-booked.v1'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM scheduling.slot WHERE id = '%s' AND status = 'busy'"
                    .formatted(slot)))
        .isEqualTo(1);
    // The 49 losers left nothing behind except their own idempotency claims being rolled back.
    assertThat(count("SELECT count(*) FROM idempotency_key")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "TC-AC-03.1: 50 retries of one request with one key and one slot create one appointment")
  void fiftyRetriesOfOneRequestCreateOneAppointment() throws Exception {
    int n = 50;
    UUID slot = freeSlot(3);
    String body = params(slot, null, "web", null);
    var pool = Executors.newFixedThreadPool(n);
    var ready = new CountDownLatch(n);
    var go = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      futures.add(
          pool.submit(() -> {
            ready.countDown();
            go.await();
            return book(patientAToken, "key-retry-0001", body).statusCode();
          }));
    }
    ready.await();
    go.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (var f : futures) {
      statuses.add(f.get(120, TimeUnit.SECONDS));
    }
    pool.shutdown();

    assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
    assertThat(statuses).filteredOn(s -> s == 200).hasSize(n - 1);
    assertThat(count("SELECT count(*) FROM scheduling.appointment")).isEqualTo(1);
  }
}
