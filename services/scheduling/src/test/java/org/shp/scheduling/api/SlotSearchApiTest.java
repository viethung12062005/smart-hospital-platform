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
import org.shp.scheduling.infra.FakePractitionerRoleClient;
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
 * End-to-end integration tests for the slot search API (S8, US-014): patient restrictions, filters,
 * the 31-day limit, keyset paging and authentication.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + SlotSearchApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + SlotSearchApiTest.TestSecurityConfig.AUDIENCE,
      "shp.paging.token-key=test-only-page-token-key-32-bytes-minimum!!"
    })
@Testcontainers
@Import(SlotSearchApiTest.TestSecurityConfig.class)
class SlotSearchApiTest {

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
  @Autowired FakePractitionerRoleClient practitionerRoles;
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

  @BeforeEach
  void cleanup() {
    jdbc.sql(
            "TRUNCATE scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history CASCADE")
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

  private HttpResponse<String> send(HttpRequest req) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
  }

  private URI url(String path) {
    return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
  }

  private String createScheduleFor(UUID doctorId, String nurseSub) throws Exception {
    String requestBody =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "cardiology" }] }]
        }
        """
            .formatted(doctorId);

    var res =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(nurseSub, List.of("nurse")))
                .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build());
    assertThat(res.statusCode()).isEqualTo(201);
    return JSON.readTree(res.body()).path("id").asString().replace("Schedule/", "");
  }

  private HttpResponse<String> putTemplates(
      String scheduleId, String accessToken, String ifMatch, String jsonBody) throws Exception {
    var b =
        HttpRequest.newBuilder(url("/api/v1/scheduling/schedules/" + scheduleId + "/templates"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
    if (ifMatch != null) {
      b.header(HttpHeaders.IF_MATCH, ifMatch);
    }
    return send(b.PUT(HttpRequest.BodyPublishers.ofString(jsonBody)).build());
  }

  private static final Instant NOW = Instant.now();

  private HttpResponse<String> get(String pathAndQuery, String accessToken) throws Exception {
    var b = HttpRequest.newBuilder(url(pathAndQuery)).GET();
    if (accessToken != null) {
      b.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }
    return send(b.build());
  }

  private String sub(String role) {
    String sub = "slot-" + role + "-" + UUID.randomUUID();
    String type = role.equals("patient") ? "Patient" : "Practitioner";
    identityLinks.links.put(sub, new IdentityLink(type, UUID.randomUUID().toString(), false));
    return token(sub, List.of(role));
  }

  private UUID schedule(String specialty) throws Exception {
    UUID docId = UUID.randomUUID();
    practitionerRoles.register(docId, specialty);
    return UUID.fromString(createScheduleFor(docId, nurseSub()));
  }

  private String nurseSub() {
    String sub = "slot-seed-nurse";
    identityLinks.links.put(
        sub, new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    return sub;
  }

  private UUID practitionerOf(UUID scheduleId) {
    return jdbc.sql("SELECT practitioner_id FROM scheduling.schedule WHERE id = ?")
        .param(scheduleId)
        .query(UUID.class)
        .single();
  }

  /** Inserts one slot; the stored FHIR content carries an internal comment. */
  private UUID slot(UUID scheduleId, String specialty, String status, Instant start) {
    UUID id = UUID.randomUUID();
    Slot slot = new Slot();
    slot.setId("Slot/" + id);
    slot.setStatus(Slot.SlotStatus.fromCode(status));
    slot.setSchedule(new Reference("Schedule/" + scheduleId));
    slot.setStart(Date.from(start));
    slot.setEnd(Date.from(start.plusSeconds(900)));
    slot.setComment("internal note");
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

  private static String day(long plusDays) {
    return LocalDate.ofInstant(NOW.plus(plusDays, ChronoUnit.DAYS), ZoneOffset.UTC).toString();
  }

  private static List<String> ids(JsonNode bundle) {
    List<String> ids = new ArrayList<>();
    for (JsonNode e : bundle.path("entry")) {
      ids.add(e.path("resource").path("id").asString());
    }
    return ids;
  }

  @Test
  @DisplayName("S8: a patient sees only free slots that start in the future")
  void patientSeesOnlyFreeFutureSlots() throws Exception {
    UUID s = schedule("cardiology");
    UUID free = slot(s, "cardiology", "free", NOW.plus(2, ChronoUnit.HOURS));
    slot(s, "cardiology", "busy", NOW.plus(3, ChronoUnit.HOURS));
    slot(s, "cardiology", "busy-unavailable", NOW.plus(4, ChronoUnit.HOURS));
    slot(s, "cardiology", "free", NOW.minus(5, ChronoUnit.HOURS));
    String patient = sub("patient");

    for (String q :
        List.of(
            "/fhir/Slot?specialty=cardiology",
            "/fhir/Slot?specialty=cardiology&status=busy",
            "/fhir/Slot?specialty=cardiology&status=free,busy,busy-unavailable",
            "/fhir/Slot?specialty=cardiology&start=ge" + day(-1) + "&start=lt" + day(2))) {
      var res = get(q, patient);
      assertThat(res.statusCode()).as(q).isEqualTo(200);
      JsonNode bundle = JSON.readTree(res.body());
      assertThat(bundle.path("resourceType").asString()).isEqualTo("Bundle");
      assertThat(bundle.path("type").asString()).isEqualTo("searchset");
      assertThat(ids(bundle)).as(q).containsExactly(free.toString());
      JsonNode resource = bundle.path("entry").get(0).path("resource");
      assertThat(resource.path("status").asString()).isEqualTo("free");
      assertThat(resource.has("comment")).as("shortened slot has no comment").isFalse();
    }
  }

  @Test
  @DisplayName("S8: nurse, doctor and admin may ask for busy and blocked slots")
  void staffMaySearchOtherStatuses() throws Exception {
    UUID s = schedule("cardiology");
    UUID free = slot(s, "cardiology", "free", NOW.plus(1, ChronoUnit.HOURS));
    UUID busy = slot(s, "cardiology", "busy", NOW.plus(2, ChronoUnit.HOURS));
    UUID blocked = slot(s, "cardiology", "busy-unavailable", NOW.plus(3, ChronoUnit.HOURS));

    for (String role : List.of("nurse", "doctor", "admin")) {
      String token = sub(role);
      assertThat(ids(JSON.readTree(get("/fhir/Slot?specialty=cardiology", token).body())))
          .as(role + " default is free")
          .containsExactly(free.toString());
      assertThat(
              ids(
                  JSON.readTree(
                      get("/fhir/Slot?specialty=cardiology&status=busy,busy-unavailable", token)
                          .body())))
          .as(role)
          .containsExactly(busy.toString(), blocked.toString());
    }
  }

  @Test
  @DisplayName("S8: 400 for a missing filter, a range over 31 days, and bad parameters")
  void invalidRequestsReturn400() throws Exception {
    String patient = sub("patient");
    for (String q :
        List.of(
            "/fhir/Slot",
            "/fhir/Slot?status=free",
            "/fhir/Slot?specialty=cardiology&start=ge" + day(0) + "&start=lt" + day(40),
            "/fhir/Slot?specialty=cardiology&start=ge" + day(0) + "&start=lt" + day(0),
            "/fhir/Slot?specialty=cardiology&_count=101",
            "/fhir/Slot?specialty=cardiology&_page_token=garbage",
            "/fhir/Slot?practitioner=Practitioner/not-a-uuid",
            "/fhir/Slot?specialty=cardiology&unknown=1")) {
      var res = get(q, patient);
      assertThat(res.statusCode()).as(q).isEqualTo(400);
      assertThat(JSON.readTree(res.body()).path("resourceType").asString())
          .isEqualTo("OperationOutcome");
    }
    var unknown = get("/fhir/Slot?specialty=cardiology&unknown=1", patient);
    assertThat(
            JSON.readTree(unknown.body())
                .path("issue")
                .path(0)
                .path("details")
                .path("coding")
                .path(0)
                .path("code")
                .asString())
        .isEqualTo("unsupported-parameter");

    // a staff status outside the slot value set is rejected; for a patient it is ignored (forced
    // free)
    assertThat(get("/fhir/Slot?specialty=cardiology&status=cancelled", sub("nurse")).statusCode())
        .isEqualTo(400);
    assertThat(get("/fhir/Slot?specialty=cardiology&status=cancelled", patient).statusCode())
        .isEqualTo(200);

    // exactly 31 days is accepted
    assertThat(
            get(
                    "/fhir/Slot?specialty=cardiology&start=ge" + day(0) + "&start=lt" + day(31),
                    patient)
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("S8: filters by practitioner and schedule; no token is 401")
  void filtersByPractitionerAndScheduleAndNeedsAuthentication() throws Exception {
    UUID a = schedule("cardiology");
    UUID b = schedule("cardiology");
    UUID slotA = slot(a, "cardiology", "free", NOW.plus(1, ChronoUnit.HOURS));
    UUID slotB = slot(b, "cardiology", "free", NOW.plus(2, ChronoUnit.HOURS));
    String patient = sub("patient");

    assertThat(ids(JSON.readTree(get("/fhir/Slot?schedule=Schedule/" + a, patient).body())))
        .containsExactly(slotA.toString());
    assertThat(
            ids(
                JSON.readTree(
                    get("/fhir/Slot?practitioner=Practitioner/" + practitionerOf(b), patient)
                        .body())))
        .containsExactly(slotB.toString());
    assertThat(ids(JSON.readTree(get("/fhir/Slot?specialty=cardiology", patient).body())))
        .containsExactly(slotA.toString(), slotB.toString());
    assertThat(get("/fhir/Slot?specialty=cardiology", null).statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("S8: keyset paging follows Bundle.link[next] with no gaps or repeats")
  void pagesThroughBundleNextLinks() throws Exception {
    UUID s = schedule("cardiology");
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      expected.add(slot(s, "cardiology", "free", NOW.plus(1 + i, ChronoUnit.HOURS)).toString());
    }
    String patient = sub("patient");

    List<String> seen = new ArrayList<>();
    String next = "/fhir/Slot?specialty=cardiology&_count=3";
    int pages = 0;
    while (next != null) {
      var res = get(next, patient);
      assertThat(res.statusCode()).isEqualTo(200);
      JsonNode bundle = JSON.readTree(res.body());
      seen.addAll(ids(bundle));
      pages++;
      next = null;
      for (JsonNode link : bundle.path("link")) {
        if ("next".equals(link.path("relation").asString())) {
          URI uri = URI.create(link.path("url").asString());
          next = uri.getRawPath() + "?" + uri.getRawQuery();
        }
      }
    }
    assertThat(pages).isEqualTo(3);
    assertThat(seen).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("TC-AC-FS.1: 14 free slots tomorrow are returned in time order")
  void fourteenFreeSlotsTomorrowAreReturnedInTimeOrder() throws Exception {
    UUID s = schedule("cardiology");
    Instant first = NOW.plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 14; i++) {
      expected.add(slot(s, "cardiology", "free", first.plusSeconds(i * 900L)).toString());
    }
    var res =
        get(
            "/fhir/Slot?specialty=cardiology&start=ge"
                + day(0)
                + "&start=lt"
                + day(3)
                + "&_count=50",
            sub("patient"));
    assertThat(res.statusCode()).isEqualTo(200);
    JsonNode bundle = JSON.readTree(res.body());
    assertThat(ids(bundle)).containsExactlyElementsOf(expected);
    for (int i = 1; i < 14; i++) {
      assertThat(bundle.path("entry").get(i).path("resource").path("start").asString())
          .isGreaterThan(bundle.path("entry").get(i - 1).path("resource").path("start").asString());
    }
  }

  @Test
  @DisplayName("TC-AC-FS.2: a patient gets no slot later than today + 30 days; staff do")
  void patientGetsNothingBeyondTheBookingWindow() throws Exception {
    UUID s = schedule("cardiology");
    UUID near = slot(s, "cardiology", "free", NOW.plus(29, ChronoUnit.DAYS));
    UUID far = slot(s, "cardiology", "free", NOW.plus(35, ChronoUnit.DAYS));
    String window = "/fhir/Slot?specialty=cardiology&start=ge" + day(20) + "&start=lt" + day(40);
    assertThat(ids(JSON.readTree(get(window, sub("patient")).body())))
        .containsExactly(near.toString());
    assertThat(ids(JSON.readTree(get(window, sub("nurse")).body())))
        .containsExactly(near.toString(), far.toString());
    String beyond = "/fhir/Slot?specialty=cardiology&start=ge" + day(33) + "&start=lt" + day(37);
    var empty = get(beyond, sub("patient"));
    assertThat(empty.statusCode()).isEqualTo(200);
    assertThat(ids(JSON.readTree(empty.body()))).isEmpty();
  }
}
