package org.shp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * GW-02: wrong token -> 401; exceeding the limit -> 429 (07 §6, NFR-S3), counters on real Redis.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
class GatewayRateLimitTest {
  static final byte[] KEY =
      "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(
              DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_REDIS_IMAGE", "redis:7-alpine")))
          .withExposedPorts(6379);

  static final HttpServer UPSTREAM;

  static {
    try {
      UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      UPSTREAM.createContext(
          "/",
          ex -> {
            byte[] b = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
          });
      UPSTREAM.start();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterAll
  static void stop() {
    UPSTREAM.stop(0);
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    String up = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
    r.add("SHP_UPSTREAM_SCHEDULING", () -> up);
    r.add("SHP_UPSTREAM_CLINICAL", () -> up);
    r.add("SHP_UPSTREAM_AI", () -> up);
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @TestConfiguration
  static class Keys {
    @Bean
    @Primary
    ReactiveJwtDecoder testDecoder(ShpAuthProperties props, Clock clock) {
      var d =
          NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
              .macAlgorithm(MacAlgorithm.HS256)
              .build();
      d.setJwtValidator(ShpJwt.validator(props, clock));
      return d;
    }
  }

  @Autowired Environment env;

  /** One user per test so counters do not affect each other. */
  static String token(String sub) throws Exception {
    var c =
        new JWTClaimsSet.Builder()
            .issuer("https://idp.test/realms/shp")
            .subject(sub)
            .audience("shp-api")
            .claim("roles", List.of("patient"))
            .expirationTime(Date.from(Instant.now().plusSeconds(600)))
            .build();
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), c);
    jwt.sign(new MACSigner(KEY));
    return jwt.serialize();
  }

  HttpResponse<String> send(String method, String path, String token, String... headers)
      throws Exception {
    var b =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
            .method(
                method,
                "GET".equals(method)
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString("{}"));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    if (headers.length > 0) {
      b.headers(headers);
    }
    try (var c = HttpClient.newHttpClient()) {
      return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  List<Integer> burst(int n, String method, String path, String token, String... headers)
      throws Exception {
    var codes = new ArrayList<Integer>();
    for (int i = 0; i < n; i++) {
      codes.add(send(method, path, token, headers).statusCode());
    }
    return codes;
  }

  @Test
  void defaultLimitAllowsTheBurstThenReturns429WithRetryAfter() throws Exception {
    String t = token("u-" + UUID.randomUUID());
    List<Integer> codes = burst(30, "GET", "/fhir/Slot", t);
    assertThat(codes).containsOnly(200);
    // While sending 30 requests the bucket has already replenished a few tokens (2/second); sending
    // faster than the replenish rate
    // must hit 429. 60 requests are ample even on a slow CI machine (only if each request takes >
    // 0.5 seconds would it not hit).
    var limited = send("GET", "/fhir/Slot", t);
    int extra = 1;
    while (limited.statusCode() == 200 && extra < 60) {
      limited = send("GET", "/fhir/Slot", t);
      extra++;
    }
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Retry-After")).hasValue("1");
    assertThat(limited.headers().firstValue("Content-Type"))
        .hasValueSatisfying(ct -> assertThat(ct).startsWith("application/problem+json"));
    assertThat(limited.body()).contains("\"code\":\"rate-limited\"").contains("\"status\":429");
  }

  @Test
  void limitsAreCountedPerUser() throws Exception {
    String a = token("u-" + UUID.randomUUID());
    String b = token("u-" + UUID.randomUUID());
    burst(40, "GET", "/fhir/Slot", a);
    assertThat(send("GET", "/fhir/Slot", b).statusCode()).isEqualTo(200);
  }

  @Test
  void invalidTokenIs401NotRateLimited() throws Exception {
    // authentication runs before rate limit: a wrong token never becomes 429 and does not consume
    // quota
    assertThat(burst(40, "GET", "/fhir/Slot", "not-a-jwt")).containsOnly(401);
    assertThat(burst(5, "GET", "/fhir/Slot", null)).containsOnly(401);
  }

  @Test
  void bookingIsLimitedTo10PerMinute() throws Exception {
    String t = token("u-" + UUID.randomUUID());
    List<Integer> codes = burst(11, "POST", "/fhir/Appointment/$book", t);
    assertThat(codes.subList(0, 10)).containsOnly(200);
    assertThat(codes.get(10)).isEqualTo(429);
    var r = send("POST", "/fhir/Appointment/$book", t);
    assertThat(r.headers().firstValue("Retry-After")).hasValue("6");
    // the booking limit does not eat into the same user's default quota
    assertThat(send("GET", "/fhir/Slot", t).statusCode()).isEqualTo(200);
  }

  @Test
  void registrationIsLimitedTo5PerHourPerClientIp() throws Exception {
    String ip = "203.0.113." + (1 + (int) (Math.random() * 250));
    List<Integer> codes =
        burst(
            6,
            "POST",
            "/api/v1/registration",
            token("u-" + UUID.randomUUID()),
            "X-Forwarded-For",
            ip + ", 10.0.0.1");
    assertThat(codes.subList(0, 5)).containsOnly(200);
    assertThat(codes.get(5)).isEqualTo(429);
    var r =
        send(
            "POST", "/api/v1/registration", token("u-" + UUID.randomUUID()), "X-Forwarded-For", ip);
    assertThat(r.headers().firstValue("Retry-After")).hasValue("720");
    // a different IP can still register
    assertThat(
            send(
                    "POST",
                    "/api/v1/registration",
                    token("u-" + UUID.randomUUID()),
                    "X-Forwarded-For",
                    "198.51.100.7")
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void policyFollowsTheTableIn07Section6() {
    assertThat(policy("POST", "/fhir/Appointment/$book")).isEqualTo(RateLimitFilter.BOOKING);
    assertThat(policy("POST", "/fhir/Appointment/abc/$reschedule"))
        .isEqualTo(RateLimitFilter.BOOKING);
    assertThat(policy("POST", "/fhir/Patient/_search")).isEqualTo(RateLimitFilter.PATIENT_SEARCH);
    assertThat(policy("GET", "/api/v1/ai/conversations")).isEqualTo(RateLimitFilter.AI);
    assertThat(policy("POST", "/api/v1/registration")).isEqualTo(RateLimitFilter.REGISTRATION);
    assertThat(policy("GET", "/fhir/Appointment/$book")).isEqualTo(RateLimitFilter.DEFAULT);
    assertThat(policy("GET", "/fhir/Slot")).isEqualTo(RateLimitFilter.DEFAULT);
    // number of requests per minute / hour matches the table
    assertThat(perMinute(RateLimitFilter.DEFAULT)).isEqualTo(120);
    assertThat(perMinute(RateLimitFilter.BOOKING)).isEqualTo(10);
    assertThat(perMinute(RateLimitFilter.PATIENT_SEARCH)).isEqualTo(60);
    assertThat(perMinute(RateLimitFilter.AI)).isEqualTo(20);
    assertThat(perMinute(RateLimitFilter.REGISTRATION) * 60).isEqualTo(5);
  }

  static RateLimitFilter.Policy policy(String method, String path) {
    return RateLimitFilter.policyFor(
        MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path)
            .build());
  }

  static double perMinute(RateLimitFilter.Policy p) {
    return 60.0 * p.replenishPerSecond() / p.requestedTokens();
  }
}
