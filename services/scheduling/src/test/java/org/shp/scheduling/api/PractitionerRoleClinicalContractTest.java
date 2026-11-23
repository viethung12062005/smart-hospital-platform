package org.shp.scheduling.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.domain.PractitionerRoleClient;
import org.shp.scheduling.infra.HttpPractitionerRoleClient;
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
 * Contract test verifying that {@link org.shp.scheduling.app.ScheduleService} uses the real {@link
 * HttpPractitionerRoleClient} wired against clinical's {@code GET
 * /internal/v1/practitioners/{id}/roles} contract (09 §4) with service-token authentication.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + PractitionerRoleClinicalContractTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + PractitionerRoleClinicalContractTest.TestSecurityConfig.AUDIENCE,
      "shp.paging.token-key=test-only-page-token-key-32-bytes-minimum!!",
      "shp.auth.client.client-id=svc-scheduling",
      "shp.auth.client.client-secret=test-only-client-secret"
    })
@Testcontainers
@Import(PractitionerRoleClinicalContractTest.TestSecurityConfig.class)
class PractitionerRoleClinicalContractTest {

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
  private static final AtomicInteger TOKEN_SEQ = new AtomicInteger();
  private static final List<String> CLINICAL_REQUEST_PATHS = new CopyOnWriteArrayList<>();
  private static final List<String> CLINICAL_AUTH_HEADERS = new CopyOnWriteArrayList<>();
  private static final AtomicReference<BiFunction<String, String, StubResponse>> CLINICAL_HANDLER =
      new AtomicReference<>();
  private static volatile HttpServer mockServer;

  @DynamicPropertySource
  static void registerMockClinicalProperties(DynamicPropertyRegistry registry) {
    String baseUrl = ensureMockServerStarted();
    registry.add(
        "shp.auth.jwk-set-uri", () -> baseUrl + "/realms/shp/protocol/openid-connect/certs");
    registry.add("shp.clinical.url", () -> baseUrl);
  }

  private static synchronized String ensureMockServerStarted() {
    if (mockServer == null) {
      try {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
            "/realms/shp/protocol/openid-connect/token",
            exchange -> {
              exchange.getRequestBody().readAllBytes();
              String accessToken = "svc-token-" + TOKEN_SEQ.incrementAndGet();
              byte[] body =
                  ("{\"access_token\":\""
                          + accessToken
                          + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
              exchange.close();
            });
        server.createContext(
            "/internal/v1/practitioners/",
            exchange -> {
              String path = exchange.getRequestURI().getPath();
              String authHeader = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
              CLINICAL_REQUEST_PATHS.add(path);
              CLINICAL_AUTH_HEADERS.add(authHeader != null ? authHeader : "");
              StubResponse response = CLINICAL_HANDLER.get().apply(path, authHeader);
              byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.getResponseHeaders().set("Cache-Control", "no-store");
              exchange.sendResponseHeaders(response.status(), body.length);
              exchange.getResponseBody().write(body);
              exchange.close();
            });
        server.start();
        mockServer = server;
      } catch (IOException e) {
        throw new IllegalStateException("Failed to start mock clinical/token server", e);
      }
    }
    return "http://127.0.0.1:" + mockServer.getAddress().getPort();
  }

  @AfterAll
  static synchronized void stopMockServer() {
    if (mockServer != null) {
      mockServer.stop(0);
      mockServer = null;
    }
  }

  @Autowired Environment env;
  @Autowired PractitionerRoleClient practitionerRoleClient;
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

  @BeforeEach
  void setUp() {
    jdbc.sql(
            "TRUNCATE scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history CASCADE")
        .update();
    identityLinks.links.clear();
    identityLinks.links.put(
        "nurse-1", new IdentityLink("Practitioner", UUID.randomUUID().toString(), false));
    CLINICAL_REQUEST_PATHS.clear();
    CLINICAL_AUTH_HEADERS.clear();
    CLINICAL_HANDLER.set((path, auth) -> new StubResponse(200, "[]"));
  }

  @Test
  @DisplayName("real HttpPractitionerRoleClient bean is wired when test fake is absent")
  void realClientBeanIsWired() {
    assertThat(practitionerRoleClient).isInstanceOf(HttpPractitionerRoleClient.class);
  }

  @Test
  @DisplayName("201 Created when clinical returns an active PractitionerRole matching specialty")
  void createsScheduleWhenClinicalReturnsActiveMatchingRole() throws Exception {
    UUID doctorId = UUID.randomUUID();
    CLINICAL_HANDLER.set((path, auth) ->
        new StubResponse(
            200,
            """
            [
              {"id":"role-inactive","specialty":"dermatology","code":"doctor","active":false},
              {"id":"role-active","specialty":"cardiology","code":"doctor","active":true}
            ]
            """));

    var res = postCreateSchedule(doctorId, "cardiology");

    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(CLINICAL_REQUEST_PATHS)
        .containsExactly("/internal/v1/practitioners/" + doctorId + "/roles");
    assertThat(CLINICAL_AUTH_HEADERS).singleElement().asString().startsWith("Bearer svc-token-");
  }

  @Test
  @DisplayName(
      "400 validation-failed when clinical role is inactive, different specialty, empty, or 404")
  void returns400WhenPractitionerLacksActiveMatchingSpecialty() throws Exception {
    List<StubResponse> nonQualifyingResponses =
        List.of(
            new StubResponse(
                200,
                "[{\"id\":\"role-1\",\"specialty\":\"cardiology\",\"code\":\"doctor\",\"active\":false}]"),
            new StubResponse(
                200,
                "[{\"id\":\"role-2\",\"specialty\":\"pediatrics\",\"code\":\"doctor\",\"active\":true}]"),
            new StubResponse(200, "[]"),
            new StubResponse(404, "{}"));

    for (StubResponse clinicalStub : nonQualifyingResponses) {
      UUID doctorId = UUID.randomUUID();
      CLINICAL_HANDLER.set((path, auth) -> clinicalStub);

      var res = postCreateSchedule(doctorId, "cardiology");

      assertThat(res.statusCode()).isEqualTo(400);
      JsonNode outcome = JSON.readTree(res.body());
      assertThat(outcome.path("resourceType").asString()).isEqualTo("OperationOutcome");
      assertThat(outcome.path("issue").get(0).path("code").asString()).isEqualTo("invalid");
    }
  }

  @Test
  @DisplayName("503 dependency-unavailable when clinical returns 503")
  void returns503WhenClinicalIsUnavailable() throws Exception {
    UUID doctorId = UUID.randomUUID();
    CLINICAL_HANDLER.set((path, auth) -> new StubResponse(503, "{\"error\":\"unavailable\"}"));

    var res = postCreateSchedule(doctorId, "cardiology");

    assertThat(res.statusCode()).isEqualTo(503);
    JsonNode outcome = JSON.readTree(res.body());
    assertThat(outcome.path("resourceType").asString()).isEqualTo("OperationOutcome");
  }

  @Test
  @DisplayName("retries once with refreshed service token after 401 from clinical")
  void retriesWithRefreshedServiceTokenAfter401() throws Exception {
    UUID doctorId = UUID.randomUUID();
    AtomicBoolean rejectedFirstCall = new AtomicBoolean(false);
    AtomicReference<String> rejectedAuthHeader = new AtomicReference<>();

    CLINICAL_HANDLER.set((path, auth) -> {
      if (rejectedFirstCall.compareAndSet(false, true)) {
        rejectedAuthHeader.set(auth);
        return new StubResponse(401, "{}");
      }
      return new StubResponse(
          200,
          "[{\"id\":\"role-1\",\"specialty\":\"cardiology\",\"code\":\"doctor\",\"active\":true}]");
    });

    var res = postCreateSchedule(doctorId, "cardiology");

    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(CLINICAL_AUTH_HEADERS).hasSize(2);
    assertThat(CLINICAL_AUTH_HEADERS.get(1)).isNotEqualTo(rejectedAuthHeader.get());
  }

  private HttpResponse<String> postCreateSchedule(UUID doctorId, String specialtyCode)
      throws Exception {
    String requestBody =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s", "display": "Dr. Le Minh Quan" }],
          "specialty": [{ "coding": [{ "system": "https://shp.example.org/fhir/CodeSystem/specialty", "code": "%s" }] }]
        }
        """
            .formatted(doctorId, specialtyCode);

    var req =
        HttpRequest.newBuilder(url("/fhir/Schedule"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("nurse-1", List.of("nurse")))
            .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    try (var http = HttpClient.newHttpClient()) {
      return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
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

  private URI url(String path) {
    return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
  }

  private record StubResponse(int status, String body) {}
}
