package org.shp.scheduling.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Slot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.BookingService;
import org.shp.scheduling.app.SlotBlockService;
import org.shp.scheduling.domain.BookingCommand;
import org.shp.scheduling.infra.FakePractitionerRoleClient;
import org.shp.scheduling.infra.SlotRepository;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.shp.web.auth.IdentityLink;
import org.shp.web.auth.IdentityLinkClient;
import org.shp.web.idempotency.IdempotencyKeys;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SCH-27 (NFR-S2): verifies that when Redis is down / unreachable, {@code GET /health/ready}
 * remains 200 OK, {@code GET /fhir/Slot} falls back cleanly to PostgreSQL, and slot writes ({@code
 * $book}, {@code $block}) still succeed while recording cache error metrics.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + SlotSearchCacheFallbackTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + SlotSearchCacheFallbackTest.TestSecurityConfig.AUDIENCE,
      "shp.paging.token-key=test-only-page-token-key-32-bytes-minimum!!",
      "spring.data.redis.timeout=200ms",
      "spring.data.redis.connect-timeout=200ms"
    })
@Testcontainers
@Import(SlotSearchCacheFallbackTest.TestSecurityConfig.class)
class SlotSearchCacheFallbackTest {

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

  private static final int CLOSED_REDIS_PORT = allocateClosedPort();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void redisProps(DynamicPropertyRegistry r) {
    r.add("spring.data.redis.host", () -> "127.0.0.1");
    r.add("spring.data.redis.port", () -> CLOSED_REDIS_PORT);
  }

  private static int allocateClosedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to allocate closed port", e);
    }
  }

  @Autowired Environment env;
  @Autowired TestSecurityConfig.FakeIdentityLinkClient identityLinks;
  @Autowired FakePractitionerRoleClient practitionerRoles;
  @Autowired JdbcClient jdbc;
  @Autowired BookingService bookingService;
  @Autowired SlotBlockService slotBlockService;
  @Autowired SlotRepository slotRepository;
  @Autowired MeterRegistry meters;

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
    new org.shp.scheduling.app.BookingTestSupport(jdbc).clean();
    identityLinks.links.clear();
    practitionerRoles.clear();
  }

  @Test
  @DisplayName(
      "Redis down keeps /health/ready up, falls back to PostgreSQL on S8, and never breaks $book/$block")
  void redisDownFallsBackToDatabaseAndKeepsReadinessAndWritesWorking() throws Exception {
    assertThat(send(HttpRequest.newBuilder(url("/health/ready")).GET().build()).statusCode())
        .isEqualTo(200);

    UUID doctorId = UUID.randomUUID();
    var support = new org.shp.scheduling.app.BookingTestSupport(jdbc);
    UUID scheduleId = support.schedule(doctorId, "cardiology");
    Instant slot1Start = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    Instant slot2Start = slot1Start.plus(1, ChronoUnit.HOURS);
    UUID slot1 = insertSlot(scheduleId, "cardiology", "free", slot1Start);
    UUID slot2 = insertSlot(scheduleId, "cardiology", "free", slot2Start);

    String patientSub = "pat-fallback-" + UUID.randomUUID();
    UUID patientId = UUID.randomUUID();
    identityLinks.links.put(patientSub, new IdentityLink("Patient", patientId.toString(), false));
    String patientToken = token(patientSub, List.of("patient"));

    String dayStart = LocalDate.ofInstant(slot1Start, ZoneOffset.UTC).minusDays(1).toString();
    String dayEnd = LocalDate.ofInstant(slot2Start, ZoneOffset.UTC).plusDays(2).toString();

    double errorBefore =
        meters.counter("shp_slot_cache_requests_total", "outcome", "error").count();
    var searchRes =
        send(
            HttpRequest.newBuilder(
                    url(
                        "/fhir/Slot?specialty=cardiology&start=ge"
                            + dayStart
                            + "&start=lt"
                            + dayEnd))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + patientToken)
                .GET()
                .build());
    assertThat(searchRes.statusCode()).isEqualTo(200);
    List<String> returnedIds = new ArrayList<>();
    for (JsonNode entry : JSON.readTree(searchRes.body()).path("entry")) {
      returnedIds.add(entry.path("resource").path("id").asString());
    }
    assertThat(returnedIds).containsExactly(slot1.toString(), slot2.toString());
    assertThat(meters.counter("shp_slot_cache_requests_total", "outcome", "error").count())
        .isEqualTo(errorBefore + 1.0);

    // Writes and Redis invalidation failures must not break booking or blocking.
    double invErrorBefore =
        meters.counter("shp_slot_cache_invalidations_total", "outcome", "error").count();
    byte[] hash = IdempotencyKeys.hashBody("{\"s\":1}".getBytes(StandardCharsets.UTF_8));
    bookingService.book(
        new BookingCommand(
            patientSub,
            "key-" + UUID.randomUUID(),
            hash,
            slot1,
            patientId,
            "web",
            "Fallback test"));
    slotBlockService.block(slot2, 1, "Doctor unavailable");

    assertThat(meters.counter("shp_slot_cache_invalidations_total", "outcome", "error").count())
        .isEqualTo(invErrorBefore + 2.0);
    assertThat(slotRepository.findById(slot1).orElseThrow().status()).isEqualTo("busy");
    assertThat(slotRepository.findById(slot2).orElseThrow().status()).isEqualTo("busy-unavailable");
  }

  private UUID insertSlot(UUID scheduleId, String specialty, String status, Instant start) {
    UUID id = UUID.randomUUID();
    Slot slot = new Slot();
    slot.setId("Slot/" + id);
    slot.setStatus(Slot.SlotStatus.fromCode(status));
    slot.setSchedule(new Reference("Schedule/" + scheduleId));
    slot.setStart(Date.from(start));
    slot.setEnd(Date.from(start.plusSeconds(900)));
    jdbc.sql(
            """
            INSERT INTO scheduling.slot (id, version_id, last_updated, schedule_id, practitioner_id,
                                         specialty, status, start_at, end_at, content)
            SELECT ?, 1, now(), s.id, s.practitioner_id, ?, ?, ?, ?, ?::jsonb
              FROM scheduling.schedule s WHERE s.id = ?
            """)
        .params(
            id,
            specialty,
            status,
            Timestamp.from(start),
            Timestamp.from(start.plusSeconds(900)),
            FhirJson.serialize(slot),
            scheduleId)
        .update();
    return id;
  }

  private HttpResponse<String> send(HttpRequest req) throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      return http.send(req, HttpResponse.BodyHandlers.ofString());
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
