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
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
class GatewayTest {
  static final byte[] KEY =
      "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);
  static final AtomicReference<String> SEEN_AUTH = new AtomicReference<>();
  static final AtomicReference<String> SEEN_PATH = new AtomicReference<>();
  static final HttpServer UPSTREAM;

  static {
    try {
      UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      UPSTREAM.createContext(
          "/",
          ex -> {
            SEEN_AUTH.set(ex.getRequestHeaders().getFirst("Authorization"));
            SEEN_PATH.set(ex.getRequestURI().toString());
            byte[] b = "{\"from\":\"scheduling\"}".getBytes(StandardCharsets.UTF_8);
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
  static void upstreams(DynamicPropertyRegistry r) {
    r.add("SHP_UPSTREAM_SCHEDULING", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    r.add("SHP_UPSTREAM_CLINICAL", () -> "http://127.0.0.1:1"); // không có ai nghe → lỗi kết nối
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

  static String token(List<String> roles, List<String> amr, String audience) throws Exception {
    var c =
        new JWTClaimsSet.Builder()
            .issuer("https://idp.test/realms/shp")
            .subject("pat")
            .audience(audience)
            .claim("roles", roles)
            .expirationTime(Date.from(Instant.now().plusSeconds(600)));
    if (amr != null) {
      c.claim("amr", amr);
    }
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), c.build());
    jwt.sign(new MACSigner(KEY));
    return jwt.serialize();
  }

  HttpResponse<String> get(String path, String token) throws Exception {
    var b =
        HttpRequest.newBuilder(
            URI.create("http://localhost:" + env.getProperty("local.server.port") + path));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    try (var c = HttpClient.newHttpClient()) {
      return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  @Test
  void validTokenIsRoutedToTheOwningServiceWithTheTokenForwarded() throws Exception {
    String t = token(List.of("patient"), null, "shp-api");
    var r = get("/fhir/Slot?schedule=x", t);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("scheduling");
    assertThat(SEEN_PATH.get()).isEqualTo("/fhir/Slot?schedule=x");
    assertThat(SEEN_AUTH.get()).isEqualTo("Bearer " + t); // service kiểm lại token
  }

  @Test
  void missingOrInvalidTokenIs401ProblemJson() throws Exception {
    var none = get("/fhir/Slot", null);
    assertThat(none.statusCode()).isEqualTo(401);
    assertThat(none.body()).contains("\"code\":\"unauthenticated\"");
    assertThat(get("/fhir/Slot", token(List.of("patient"), null, "other-api")).statusCode())
        .isEqualTo(401);
  }

  @Test
  void staffWithoutMfaIsRejectedAtTheEdge() throws Exception {
    assertThat(get("/fhir/Slot", token(List.of("doctor"), null, "shp-api")).statusCode())
        .isEqualTo(401);
    assertThat(get("/fhir/Slot", token(List.of("doctor"), List.of("mfa"), "shp-api")).statusCode())
        .isEqualTo(200);
  }

  @Test
  void internalEndpointsAreNeverExposed() throws Exception {
    var r = get("/internal/v1/identity/abc", token(List.of("patient"), null, "shp-api"));
    assertThat(r.statusCode()).isEqualTo(404);
  }

  @Test
  void unknownPathIs404() throws Exception {
    assertThat(get("/api/v2/unknown", token(List.of("patient"), null, "shp-api")).statusCode())
        .isEqualTo(404);
  }

  @Test
  void downstreamOutageIsAGatewayError() throws Exception {
    assertThat(get("/fhir/Patient/x", token(List.of("patient"), null, "shp-api")).statusCode())
        .isBetween(500, 504);
  }

  @Test
  void healthIsPublic() throws Exception {
    assertThat(get("/health/ready", null).statusCode()).isEqualTo(200);
    assertThat(get("/info", null).body()).contains("gateway");
  }
}
