package org.shp.scheduling.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Slot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.BookingService;
import org.shp.scheduling.app.BookingTestSupport;
import org.shp.scheduling.app.SlotBlockService;
import org.shp.scheduling.app.SlotGenerator;
import org.shp.scheduling.domain.BookingCommand;
import org.shp.scheduling.infra.FakePractitionerRoleClient;
import org.shp.scheduling.infra.SlotCacheInvalidator;
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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SCH-27 (NFR-S2): Redis integration tests for S8 {@code GET /fhir/Slot} caching — 5s TTL,
 * measurable hit ratio in Micrometer / {@code /prometheus}, zero PII in Redis, and scoped
 * invalidation on booking, slot block/unblock, and {@code appointment-*} events.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + SlotSearchCacheIntegrationTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + SlotSearchCacheIntegrationTest.TestSecurityConfig.AUDIENCE,
      "shp.paging.token-key=test-only-page-token-key-32-bytes-minimum!!",
      "shp.slot-cache.ttl=5s"
    })
@Testcontainers
@Import(SlotSearchCacheIntegrationTest.TestSecurityConfig.class)
class SlotSearchCacheIntegrationTest {

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

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(
              DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_REDIS_IMAGE", "redis:7-alpine")))
          .withExposedPorts(6379);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void redisProps(DynamicPropertyRegistry r) {
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired Environment env;
  @Autowired TestSecurityConfig.FakeIdentityLinkClient identityLinks;
  @Autowired FakePractitionerRoleClient practitionerRoles;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired MeterRegistry meters;
  @Autowired BookingService bookingService;
  @Autowired SlotBlockService slotBlockService;
  @Autowired SlotCacheInvalidator cacheInvalidator;
  @Autowired SlotRepository slotRepository;

  @TestConfiguration
  static class TestSecurityConfig {
    static final String ISSUER = "https://idp.test/realms/shp";
    static final String AUDIENCE = "shp-api";
    static final byte[] KEY =
        "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);

    static final Instant FIXED_NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Bean
    @Primary
    Clock testClock() {
      return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }

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
  void reset() {
    new BookingTestSupport(jdbc).clean();
    identityLinks.links.clear();
    practitionerRoles.clear();
    try (RedisConnection conn = redis.getConnectionFactory().getConnection()) {
      conn.serverCommands().flushDb();
    }
  }

  @Test
  @DisplayName("S8 repeated search hits Redis with <=5s TTL, measurable hit ratio, and no PII")
  void repeatedSlotSearchHitsRedisCacheWith5sTtlAndMeasurableHitRatio() throws Exception {
    UUID doctorId = UUID.randomUUID();
    var support = new BookingTestSupport(jdbc);
    UUID scheduleId = support.schedule(doctorId, "cardiology");
    LocalDate vnDay = LocalDate.now(SlotGenerator.VN_ZONE).plusDays(2);
    Instant from = vnDay.atTime(8, 0).atZone(SlotGenerator.VN_ZONE).toInstant();
    Instant to = vnDay.atTime(17, 0).atZone(SlotGenerator.VN_ZONE).toInstant();
    UUID slotId = insertSlot(scheduleId, "cardiology", "free", from.plus(1, ChronoUnit.HOURS));

    String patientSub = "pat-cache-" + UUID.randomUUID();
    identityLinks.links.put(
        patientSub, new IdentityLink("Patient", UUID.randomUUID().toString(), false));
    String patientToken = token(patientSub, List.of("patient"));
    String path = "/fhir/Slot?specialty=cardiology&start=ge" + from + "&start=lt" + to;

    double hitsBefore = countRequests("hit");
    double missesBefore = countRequests("miss");

    var r1 = send(getReq(path, patientToken));
    assertThat(r1.statusCode()).isEqualTo(200);
    assertThat(slotIds(r1.body())).containsExactly(slotId.toString());
    assertThat(countRequests("miss")).isEqualTo(missesBefore + 1.0);
    assertThat(countRequests("hit")).isEqualTo(hitsBefore);

    Set<String> queryKeys = redis.keys("shp:slot:q:*");
    assertThat(queryKeys).hasSize(1);
    String queryKey = queryKeys.iterator().next();
    Long expireSeconds = redis.getExpire(queryKey, TimeUnit.SECONDS);
    assertThat(expireSeconds).isNotNull().isBetween(1L, 5L);
    String cachedValue = redis.opsForValue().get(queryKey);
    assertThat(cachedValue).doesNotContain(patientSub).doesNotContain("Patient/");

    // Mutate DB behind cache to confirm subsequent calls come from Redis within the 5s window.
    jdbc.sql("UPDATE scheduling.slot SET status = 'busy' WHERE id = ?").param(slotId).update();

    var r2 = send(getReq(path, patientToken));
    var r3 = send(getReq(path, patientToken));
    assertThat(r2.statusCode()).isEqualTo(200);
    assertThat(r3.statusCode()).isEqualTo(200);
    assertThat(slotIds(r2.body())).containsExactly(slotId.toString());
    assertThat(slotIds(r3.body())).containsExactly(slotId.toString());

    assertThat(countRequests("miss")).isEqualTo(missesBefore + 1.0);
    assertThat(countRequests("hit")).isEqualTo(hitsBefore + 2.0);
    double totalHits = countRequests("hit");
    double totalMisses = countRequests("miss");
    assertThat(meters.get("shp_slot_cache_hit_ratio").gauge().value())
        .isCloseTo(totalHits / (totalHits + totalMisses), within(1e-9));

    var metricsRes = send(HttpRequest.newBuilder(url("/prometheus")).GET().build());
    assertThat(metricsRes.statusCode()).isEqualTo(200);
    assertThat(metricsRes.body())
        .contains("shp_slot_cache_requests_total")
        .contains("shp_slot_cache_hit_ratio");
  }

  @Test
  @DisplayName("book, block/unblock, and appointment events invalidate only affected doctor/day")
  void bookingBlockAndAppointmentEventsInvalidateOnlyAffectedScopeImmediately() throws Exception {
    UUID doc1 = UUID.randomUUID();
    UUID doc2 = UUID.randomUUID();
    var support = new BookingTestSupport(jdbc);
    UUID sched1 = support.schedule(doc1, "cardiology");
    UUID sched2 = support.schedule(doc2, "pediatrics");

    LocalDate day1 = LocalDate.now(SlotGenerator.VN_ZONE).plusDays(2);
    LocalDate day2 = day1.plusDays(1);
    Instant day1From = day1.atTime(8, 0).atZone(SlotGenerator.VN_ZONE).toInstant();
    Instant day1To = day1.atTime(17, 0).atZone(SlotGenerator.VN_ZONE).toInstant();
    Instant day2From = day2.atTime(8, 0).atZone(SlotGenerator.VN_ZONE).toInstant();
    Instant day2To = day2.atTime(17, 0).atZone(SlotGenerator.VN_ZONE).toInstant();

    UUID cDay1SlotA = insertSlot(sched1, "cardiology", "free", day1From.plus(1, ChronoUnit.HOURS));
    UUID cDay1SlotB = insertSlot(sched1, "cardiology", "free", day1From.plus(2, ChronoUnit.HOURS));
    UUID cDay2Slot = insertSlot(sched1, "cardiology", "free", day2From.plus(1, ChronoUnit.HOURS));
    UUID pDay1Slot = insertSlot(sched2, "pediatrics", "free", day1From.plus(1, ChronoUnit.HOURS));

    String patientSub = "pat-inv-" + UUID.randomUUID();
    UUID patientId = UUID.randomUUID();
    identityLinks.links.put(patientSub, new IdentityLink("Patient", patientId.toString(), false));
    String patientToken = token(patientSub, List.of("patient"));

    String qCardDay1 = "/fhir/Slot?specialty=cardiology&start=ge" + day1From + "&start=lt" + day1To;
    String qCardDay2 = "/fhir/Slot?specialty=cardiology&start=ge" + day2From + "&start=lt" + day2To;
    String qPedDay1 = "/fhir/Slot?specialty=pediatrics&start=ge" + day1From + "&start=lt" + day1To;

    // Prime all 3 cache entries (3 misses).
    assertThat(slotIds(send(getReq(qCardDay1, patientToken)).body()))
        .containsExactly(cDay1SlotA.toString(), cDay1SlotB.toString());
    assertThat(slotIds(send(getReq(qCardDay2, patientToken)).body()))
        .containsExactly(cDay2Slot.toString());
    assertThat(slotIds(send(getReq(qPedDay1, patientToken)).body()))
        .containsExactly(pDay1Slot.toString());

    // Book cDay1SlotA -> invalidates cardiology Day 1 only.
    byte[] hash = IdempotencyKeys.hashBody("{\"slot\":\"a\"}".getBytes(StandardCharsets.UTF_8));
    bookingService.book(
        new BookingCommand(
            patientSub, "key-" + UUID.randomUUID(), hash, cDay1SlotA, patientId, "web", null));

    double hitsBefore = countRequests("hit");
    double missesBefore = countRequests("miss");

    // Affected query misses cache and reflects booked slot removal; unrelated day/specialty hit.
    assertThat(slotIds(send(getReq(qCardDay1, patientToken)).body()))
        .containsExactly(cDay1SlotB.toString());
    assertThat(slotIds(send(getReq(qCardDay2, patientToken)).body()))
        .containsExactly(cDay2Slot.toString());
    assertThat(slotIds(send(getReq(qPedDay1, patientToken)).body()))
        .containsExactly(pDay1Slot.toString());

    assertThat(countRequests("miss")).isEqualTo(missesBefore + 1.0);
    assertThat(countRequests("hit")).isEqualTo(hitsBefore + 2.0);

    // Block and unblock cDay1SlotB -> immediate invalidation on both transitions.
    slotBlockService.block(cDay1SlotB, 1, "Maintenance");
    assertThat(slotIds(send(getReq(qCardDay1, patientToken)).body())).isEmpty();
    slotBlockService.unblock(cDay1SlotB, 2);
    assertThat(slotIds(send(getReq(qCardDay1, patientToken)).body()))
        .containsExactly(cDay1SlotB.toString());

    // External appointment-cancelled and appointment-rescheduled CloudEvents invalidate cache too.
    double invOkBefore = countInvalidations("ok");
    jdbc.sql("UPDATE scheduling.slot SET status = 'free' WHERE id = ?").param(cDay1SlotA).update();
    CloudEvent cancelledEvt =
        CloudEvents.create(
            "scheduling",
            "shp.scheduling.appointment-cancelled.v1",
            "Appointment/" + UUID.randomUUID(),
            Map.of(
                "appointmentId", UUID.randomUUID().toString(),
                "version", 2,
                "patientId", patientId.toString(),
                "practitionerId", doc1.toString(),
                "start", day1From.plus(1, ChronoUnit.HOURS).toString(),
                "cancelReason", "patient-request",
                "cancelledBy", "patient",
                "cancelledAt", Instant.now().toString(),
                "hoursBeforeStart", 24.0));
    cacheInvalidator.onAppointmentEvent(cancelledEvt);
    assertThat(countInvalidations("ok")).isEqualTo(invOkBefore + 1.0);
    assertThat(slotIds(send(getReq(qCardDay1, patientToken)).body()))
        .containsExactly(cDay1SlotA.toString(), cDay1SlotB.toString());

    CloudEvent rescheduledEvt =
        CloudEvents.create(
            "scheduling",
            "shp.scheduling.appointment-rescheduled.v1",
            "Appointment/" + UUID.randomUUID(),
            Map.of(
                "oldAppointmentId", UUID.randomUUID().toString(),
                "newAppointmentId", UUID.randomUUID().toString(),
                "version", 2,
                "patientId", patientId.toString(),
                "practitionerId", doc1.toString(),
                "oldStart", day1From.plus(1, ChronoUnit.HOURS).toString(),
                "newStart", day2From.plus(1, ChronoUnit.HOURS).toString(),
                "newEnd", day2From.plus(2, ChronoUnit.HOURS).toString(),
                "specialty", "cardiology"));
    cacheInvalidator.onAppointmentEvent(rescheduledEvt);
    assertThat(countInvalidations("ok")).isEqualTo(invOkBefore + 2.0);
  }

  @Test
  @DisplayName("default-start searches within the same TTL bucket share the cached Redis entry")
  void defaultStartQueriesWithinBucketShareCacheEntry() throws Exception {
    UUID doctorId = UUID.randomUUID();
    var support = new BookingTestSupport(jdbc);
    UUID scheduleId = support.schedule(doctorId, "cardiology");
    UUID slotId =
        insertSlot(
            scheduleId,
            "cardiology",
            "free",
            Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS));

    String patientSub = "pat-bucket-" + UUID.randomUUID();
    identityLinks.links.put(
        patientSub, new IdentityLink("Patient", UUID.randomUUID().toString(), false));
    String patientToken = token(patientSub, List.of("patient"));
    String path = "/fhir/Slot?specialty=cardiology";

    double hitsBefore = countRequests("hit");
    double missesBefore = countRequests("miss");

    var r1 = send(getReq(path, patientToken));
    var r2 = send(getReq(path, patientToken));
    assertThat(r1.statusCode()).isEqualTo(200);
    assertThat(r2.statusCode()).isEqualTo(200);
    assertThat(slotIds(r1.body())).containsExactly(slotId.toString());
    assertThat(slotIds(r2.body())).containsExactly(slotId.toString());
    assertThat(countRequests("miss")).isEqualTo(missesBefore + 1.0);
    assertThat(countRequests("hit")).isEqualTo(hitsBefore + 1.0);
  }

  private double countRequests(String outcome) {
    return meters.counter("shp_slot_cache_requests_total", "outcome", outcome).count();
  }

  private double countInvalidations(String outcome) {
    return meters.counter("shp_slot_cache_invalidations_total", "outcome", outcome).count();
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

  private HttpRequest getReq(String pathAndQuery, String accessToken) {
    return HttpRequest.newBuilder(url(pathAndQuery))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .GET()
        .build();
  }

  private HttpResponse<String> send(HttpRequest req) throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
  }

  private URI url(String path) {
    return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
  }

  private static List<String> slotIds(String jsonBody) throws Exception {
    List<String> ids = new ArrayList<>();
    for (JsonNode e : JSON.readTree(jsonBody).path("entry")) {
      ids.add(e.path("resource").path("id").asString());
    }
    return ids;
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
