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
import java.security.MessageDigest;
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
import org.hl7.fhir.r4.model.Slot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.BookingService;
import org.shp.scheduling.domain.BookingCommand;
import org.shp.scheduling.domain.SlotRecord;
import org.shp.scheduling.infra.FakePractitionerRoleClient;
import org.shp.scheduling.infra.SlotRepository;
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
import org.springframework.http.MediaType;
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
 * Integration tests for S9 ({@code POST /fhir/Slot/{id}/$block}) and S10 ({@code POST
 * /fhir/Slot/{id}/$unblock}), covering {@code AC-02.2}, {@code If-Match}, state transitions, input
 * validation, and authorization/audit.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + SlotBlockApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + SlotBlockApiTest.TestSecurityConfig.AUDIENCE
    })
@Testcontainers
@Import(SlotBlockApiTest.TestSecurityConfig.class)
class SlotBlockApiTest {

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
  private static final Instant NOW = Instant.now();

  @Autowired Environment env;
  @Autowired TestSecurityConfig.FakeIdentityLinkClient identityLinks;
  @Autowired FakePractitionerRoleClient practitionerRoles;
  @Autowired SlotRepository slotRepository;
  @Autowired BookingService bookingService;
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
            "TRUNCATE scheduling.appointment, scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history, scheduling.outbox, scheduling.idempotency_key CASCADE")
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

  private String staffToken(String role) {
    String sub = "slot-block-" + role + "-" + UUID.randomUUID();
    String type = role.equals("patient") ? "Patient" : "Practitioner";
    identityLinks.links.put(sub, new IdentityLink(type, UUID.randomUUID().toString(), false));
    return token(sub, List.of(role));
  }

  private URI url(String path) {
    return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
  }

  private HttpResponse<String> send(HttpRequest req) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
  }

  private HttpResponse<String> postBlock(
      String slotId, String accessToken, String ifMatch, String contentType, String body)
      throws Exception {
    var b = HttpRequest.newBuilder(url("/fhir/Slot/" + slotId + "/$block"));
    if (accessToken != null) {
      b.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }
    if (ifMatch != null) {
      b.header(HttpHeaders.IF_MATCH, ifMatch);
    }
    if (contentType != null) {
      b.header(HttpHeaders.CONTENT_TYPE, contentType);
    }
    b.POST(
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return send(b.build());
  }

  private HttpResponse<String> postUnblock(String slotId, String accessToken, String ifMatch)
      throws Exception {
    var b = HttpRequest.newBuilder(url("/fhir/Slot/" + slotId + "/$unblock"));
    if (accessToken != null) {
      b.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }
    if (ifMatch != null) {
      b.header(HttpHeaders.IF_MATCH, ifMatch);
    }
    b.POST(HttpRequest.BodyPublishers.noBody());
    return send(b.build());
  }

  private UUID seedScheduleAndSlot(String status) throws Exception {
    UUID docId = UUID.randomUUID();
    practitionerRoles.register(docId, "cardiology");
    String nurseToken = staffToken("nurse");
    String scheduleBody =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "cardiology" }] }]
        }
        """
            .formatted(docId);
    var res =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + nurseToken)
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(scheduleBody))
                .build());
    assertThat(res.statusCode()).isEqualTo(201);
    UUID scheduleId =
        UUID.fromString(JSON.readTree(res.body()).path("id").asString().replace("Schedule/", ""));

    UUID slotId = UUID.randomUUID();
    Instant start = NOW.plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
    Instant end = start.plus(15, ChronoUnit.MINUTES);
    Slot fhirSlot = new Slot();
    fhirSlot.setId("Slot/" + slotId);
    fhirSlot.getMeta().setVersionId("1").setLastUpdated(Date.from(NOW));
    fhirSlot.setSchedule(new org.hl7.fhir.r4.model.Reference("Schedule/" + scheduleId));
    fhirSlot.setStatus(
        switch (status) {
          case "free" -> Slot.SlotStatus.FREE;
          case "busy" -> Slot.SlotStatus.BUSY;
          default -> Slot.SlotStatus.BUSYUNAVAILABLE;
        });
    if ("busy-unavailable".equals(status)) {
      fhirSlot.setComment("Initial maintenance");
    }
    fhirSlot.setStart(Date.from(start));
    fhirSlot.setEnd(Date.from(end));

    slotRepository.insertIfNotOverlapping(
        new SlotRecord(
            slotId,
            1,
            NOW,
            scheduleId,
            docId,
            "cardiology",
            status,
            start,
            end,
            FhirJson.serialize(fhirSlot)));
    return slotId;
  }

  private String outcomeCode(String responseBody) {
    return JSON.readTree(responseBody)
        .path("issue")
        .get(0)
        .path("details")
        .path("coding")
        .get(0)
        .path("code")
        .asString();
  }

  @Test
  void blockFreeSlotAndUnblockRoundTrip() throws Exception {
    UUID slotId = seedScheduleAndSlot("free");
    String nurse = staffToken("nurse");
    String admin = staffToken("admin");

    var blocked =
        postBlock(
            slotId.toString(),
            nurse,
            "W/\"1\"",
            MediaType.APPLICATION_JSON_VALUE,
            "{\"reason\": \"  Doctor on leave  \"}");
    assertThat(blocked.statusCode()).isEqualTo(200);
    assertThat(blocked.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
        .contains("application/fhir+json");
    assertThat(blocked.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"2\"");

    JsonNode blockedJson = JSON.readTree(blocked.body());
    assertThat(blockedJson.path("status").asString()).isEqualTo("busy-unavailable");
    assertThat(blockedJson.path("comment").asString()).isEqualTo("Doctor on leave");
    assertThat(blockedJson.path("meta").path("versionId").asString()).isEqualTo("2");

    SlotRecord inDbAfterBlock = slotRepository.findById(slotId).orElseThrow();
    assertThat(inDbAfterBlock.status()).isEqualTo("busy-unavailable");
    assertThat(inDbAfterBlock.versionId()).isEqualTo(2);

    var unblocked = postUnblock(slotId.toString(), admin, "W/\"2\"");
    assertThat(unblocked.statusCode()).isEqualTo(200);
    assertThat(unblocked.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"3\"");

    JsonNode unblockedJson = JSON.readTree(unblocked.body());
    assertThat(unblockedJson.path("status").asString()).isEqualTo("free");
    assertThat(unblockedJson.has("comment")).isFalse();
    assertThat(unblockedJson.path("meta").path("versionId").asString()).isEqualTo("3");

    // Verify application/fhir+json Content-Type works for $block too.
    var reblocked =
        postBlock(
            slotId.toString(),
            admin,
            "W/\"3\"",
            FhirJson.MEDIA_TYPE,
            "{\"reason\": \"Equipment calibration\"}");
    assertThat(reblocked.statusCode()).isEqualTo(200);
    assertThat(reblocked.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"4\"");
  }

  @Test
  void blockingBusySlotReturns409SlotHasAppointment() throws Exception {
    UUID slotId = seedScheduleAndSlot("free");
    UUID patientId = UUID.randomUUID();
    byte[] hash =
        MessageDigest.getInstance("SHA-256").digest("book-req".getBytes(StandardCharsets.UTF_8));
    bookingService.book(
        new BookingCommand(
            "patient-" + patientId,
            "idem-" + UUID.randomUUID(),
            hash,
            slotId,
            patientId,
            "web",
            null));

    SlotRecord busySlot = slotRepository.findById(slotId).orElseThrow();
    assertThat(busySlot.status()).isEqualTo("busy");
    assertThat(busySlot.versionId()).isEqualTo(2);

    var blocked =
        postBlock(
            slotId.toString(),
            staffToken("nurse"),
            "W/\"2\"",
            MediaType.APPLICATION_JSON_VALUE,
            "{\"reason\": \"Doctor on leave\"}");
    assertThat(blocked.statusCode()).isEqualTo(409);
    assertThat(outcomeCode(blocked.body())).isEqualTo("slot-has-appointment");

    SlotRecord unchanged = slotRepository.findById(slotId).orElseThrow();
    assertThat(unchanged.status()).isEqualTo("busy");
    assertThat(unchanged.versionId()).isEqualTo(2);
  }

  @Test
  void blockAndUnblockWrongStateReturns409InvalidStateTransition() throws Exception {
    String nurse = staffToken("nurse");
    UUID alreadyBlockedId = seedScheduleAndSlot("busy-unavailable");
    UUID freeId = seedScheduleAndSlot("free");
    UUID busyId = seedScheduleAndSlot("busy");

    var blockAlreadyBlocked =
        postBlock(
            alreadyBlockedId.toString(),
            nurse,
            "W/\"1\"",
            MediaType.APPLICATION_JSON_VALUE,
            "{\"reason\": \"Already blocked\"}");
    assertThat(blockAlreadyBlocked.statusCode()).isEqualTo(409);
    assertThat(outcomeCode(blockAlreadyBlocked.body())).isEqualTo("invalid-state-transition");

    var unblockFree = postUnblock(freeId.toString(), nurse, "W/\"1\"");
    assertThat(unblockFree.statusCode()).isEqualTo(409);
    assertThat(outcomeCode(unblockFree.body())).isEqualTo("invalid-state-transition");

    var unblockBusy = postUnblock(busyId.toString(), nurse, "W/\"1\"");
    assertThat(unblockBusy.statusCode()).isEqualTo(409);
    assertThat(outcomeCode(unblockBusy.body())).isEqualTo("invalid-state-transition");
  }

  @Test
  void ifMatchPreconditionEnforcement() throws Exception {
    String nurse = staffToken("nurse");
    UUID freeId = seedScheduleAndSlot("free");
    UUID blockedId = seedScheduleAndSlot("busy-unavailable");

    // Advance blockedId to version 2 via $unblock -> $block cycle isn't needed; block freeId first
    // -> v2.
    var firstBlock =
        postBlock(
            freeId.toString(),
            nurse,
            "W/\"1\"",
            MediaType.APPLICATION_JSON_VALUE,
            "{\"reason\": \"Initial block\"}");
    assertThat(firstBlock.statusCode()).isEqualTo(200);

    // Missing If-Match -> 428
    var missingOnBlock =
        postBlock(
            blockedId.toString(),
            nurse,
            null,
            MediaType.APPLICATION_JSON_VALUE,
            "{\"reason\": \"Test\"}");
    assertThat(missingOnBlock.statusCode()).isEqualTo(428);
    assertThat(outcomeCode(missingOnBlock.body())).isEqualTo("precondition-required");

    var missingOnUnblock = postUnblock(blockedId.toString(), nurse, null);
    assertThat(missingOnUnblock.statusCode()).isEqualTo(428);
    assertThat(outcomeCode(missingOnUnblock.body())).isEqualTo("precondition-required");

    // Stale version W/"1" on v2 -> 412
    var staleOnUnblock = postUnblock(freeId.toString(), nurse, "W/\"1\"");
    assertThat(staleOnUnblock.statusCode()).isEqualTo(412);
    assertThat(outcomeCode(staleOnUnblock.body())).isEqualTo("version-conflict");

    // Unblock properly so freeId becomes free v3, then test stale W/"1" on $block -> 412
    assertThat(postUnblock(freeId.toString(), nurse, "W/\"2\"").statusCode()).isEqualTo(200);
    var staleOnBlock =
        postBlock(
            freeId.toString(),
            nurse,
            "W/\"1\"",
            MediaType.APPLICATION_JSON_VALUE,
            "{\"reason\": \"Stale\"}");
    assertThat(staleOnBlock.statusCode()).isEqualTo(412);
    assertThat(outcomeCode(staleOnBlock.body())).isEqualTo("version-conflict");

    // Wildcard If-Match: * -> 412
    var wildcardBlock =
        postBlock(
            freeId.toString(),
            nurse,
            "*",
            MediaType.APPLICATION_JSON_VALUE,
            "{\"reason\": \"Wildcard\"}");
    assertThat(wildcardBlock.statusCode()).isEqualTo(412);
    assertThat(outcomeCode(wildcardBlock.body())).isEqualTo("version-conflict");
  }

  @Test
  void blockValidationErrorsAndNotFound() throws Exception {
    String nurse = staffToken("nurse");
    UUID slotId = seedScheduleAndSlot("free");
    String tooLong = "x".repeat(201);

    for (String badBody :
        List.of(
            "",
            "{}",
            "{\"reason\": \"   \"}",
            "{\"reason\": 123}",
            "{\"reason\": \"" + tooLong + "\"}",
            "{invalid-json",
            "[\"Doctor on leave\"]")) {
      var res =
          postBlock(slotId.toString(), nurse, "W/\"1\"", MediaType.APPLICATION_JSON_VALUE, badBody);
      assertThat(res.statusCode()).as("body=%s", badBody).isEqualTo(400);
      assertThat(outcomeCode(res.body())).isEqualTo("validation-failed");
    }

    String missingId = UUID.randomUUID().toString();
    assertThat(
            postBlock(
                    missingId,
                    nurse,
                    "W/\"1\"",
                    MediaType.APPLICATION_JSON_VALUE,
                    "{\"reason\": \"Leave\"}")
                .statusCode())
        .isEqualTo(404);
    assertThat(
            postBlock(
                    "not-a-uuid",
                    nurse,
                    "W/\"1\"",
                    MediaType.APPLICATION_JSON_VALUE,
                    "{\"reason\": \"Leave\"}")
                .statusCode())
        .isEqualTo(404);
    assertThat(postUnblock(missingId, nurse, "W/\"1\"").statusCode()).isEqualTo(404);
    assertThat(postUnblock("not-a-uuid", nurse, "W/\"1\"").statusCode()).isEqualTo(404);
  }

  @Test
  void blockAndUnblockAuthorizationMatrix() throws Exception {
    UUID slotId = seedScheduleAndSlot("free");

    // Unauthenticated -> 401
    assertThat(
            postBlock(
                    slotId.toString(),
                    null,
                    "W/\"1\"",
                    MediaType.APPLICATION_JSON_VALUE,
                    "{\"reason\": \"Leave\"}")
                .statusCode())
        .isEqualTo(401);
    assertThat(postUnblock(slotId.toString(), null, "W/\"1\"").statusCode()).isEqualTo(401);

    // Denied roles: patient, doctor, dpo -> 403 + denied audit outbox events
    int initialAudits = countAuditEvents();
    for (String role : List.of("patient", "doctor", "dpo")) {
      String tok = staffToken(role);
      var blockDenied =
          postBlock(
              slotId.toString(),
              tok,
              "W/\"1\"",
              MediaType.APPLICATION_JSON_VALUE,
              "{\"reason\": \"Leave\"}");
      assertThat(blockDenied.statusCode()).as("block role=%s", role).isEqualTo(403);

      var unblockDenied = postUnblock(slotId.toString(), tok, "W/\"1\"");
      assertThat(unblockDenied.statusCode()).as("unblock role=%s", role).isEqualTo(403);
    }

    int afterDeniedAudits = countAuditEvents();
    assertThat(afterDeniedAudits).isGreaterThanOrEqualTo(initialAudits + 6);
    Integer deniedCount =
        jdbc.sql(
                "SELECT count(*) FROM scheduling.outbox WHERE event_type = 'shp.audit.recorded.v1' AND payload->'data'->>'outcome' = 'denied'")
            .query(Integer.class)
            .single();
    assertThat(deniedCount).isGreaterThanOrEqualTo(6);

    // Allowed roles: nurse blocks, admin unblocks -> 200
    assertThat(
            postBlock(
                    slotId.toString(),
                    staffToken("nurse"),
                    "W/\"1\"",
                    MediaType.APPLICATION_JSON_VALUE,
                    "{\"reason\": \"Clinic closed\"}")
                .statusCode())
        .isEqualTo(200);
    assertThat(postUnblock(slotId.toString(), staffToken("admin"), "W/\"2\"").statusCode())
        .isEqualTo(200);
  }

  private int countAuditEvents() {
    return jdbc.sql(
            "SELECT count(*) FROM scheduling.outbox WHERE event_type = 'shp.audit.recorded.v1'")
        .query(Integer.class)
        .single();
  }
}
