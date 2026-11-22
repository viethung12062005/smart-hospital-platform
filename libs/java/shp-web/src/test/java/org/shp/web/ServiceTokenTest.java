package org.shp.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.web.auth.ServiceTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * SHR-32: the auto-configured interceptor, built the way a service's PlatformConfig does, attaches
 * a client-credentials token to the call to clinical. The token endpoint is derived from the JWKS
 * address the way the local cluster configures it; the client id defaults to {@code svc-<service>}.
 */
@SpringBootTest(
    classes = TestApp.class,
    properties = {
      "spring.application.name=scheduling",
      "shp.auth.authority=" + TestApp.ISSUER,
      "shp.auth.audience=" + TestApp.AUDIENCE,
      "shp.auth.client.client-secret=test-only-secret"
    })
class ServiceTokenTest {
  static final HttpServer SERVER = start();
  static final AtomicInteger TOKENS = new AtomicInteger();
  static final List<String> TOKEN_REQUEST_AUTH = new CopyOnWriteArrayList<>();
  static final List<String> CLINICAL_AUTH = new CopyOnWriteArrayList<>();

  /** Status the fake clinical answers for the next call; 401 once makes the client retry. */
  static volatile int clinicalStatus = 200;

  static HttpServer start() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext(
          "/realms/shp/protocol/openid-connect/token",
          ex -> {
            TOKEN_REQUEST_AUTH.add(ex.getRequestHeaders().getFirst("Authorization"));
            ex.getRequestBody().readAllBytes();
            byte[] out =
                ("{\"access_token\":\"token-" + TOKENS.incrementAndGet() + "\",\"expires_in\":300}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
          });
      s.createContext(
          "/internal/v1/identity/abc",
          ex -> {
            CLINICAL_AUTH.add(ex.getRequestHeaders().getFirst("Authorization"));
            int status = clinicalStatus;
            if (status == 401) {
              clinicalStatus = 200;
            }
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
          });
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add(
        "shp.auth.jwk-set-uri",
        () ->
            "http://127.0.0.1:"
                + SERVER.getAddress().getPort()
                + "/realms/shp/protocol/openid-connect/certs");
  }

  @AfterAll
  static void stop() {
    SERVER.stop(0);
  }

  @Autowired ServiceTokenInterceptor interceptor;

  @BeforeEach
  void reset() {
    TOKEN_REQUEST_AUTH.clear();
    CLINICAL_AUTH.clear();
    clinicalStatus = 200;
  }

  RestClient clinical() {
    return RestClient.builder()
        .baseUrl("http://127.0.0.1:" + SERVER.getAddress().getPort())
        .requestInterceptor(interceptor)
        .build();
  }

  @Test
  void callCarriesTheServiceTokenFromTheDerivedEndpointAndClientId() {
    clinical().get().uri("/internal/v1/identity/abc").retrieve().toBodilessEntity();
    assertThat(CLINICAL_AUTH).hasSize(1);
    assertThat(CLINICAL_AUTH.get(0)).startsWith("Bearer token-");
    String basic =
        new String(
            Base64.getDecoder().decode(TOKEN_REQUEST_AUTH.get(0).substring("Basic ".length())),
            StandardCharsets.UTF_8);
    assertThat(basic).isEqualTo("svc-scheduling:test-only-secret");
  }

  @Test
  void tokenIsReusedAcrossCalls() {
    var client = clinical();
    client.get().uri("/internal/v1/identity/abc").retrieve().toBodilessEntity();
    client.get().uri("/internal/v1/identity/abc").retrieve().toBodilessEntity();
    assertThat(CLINICAL_AUTH).hasSize(2);
    assertThat(CLINICAL_AUTH.get(1)).isEqualTo(CLINICAL_AUTH.get(0));
    assertThat(TOKEN_REQUEST_AUTH).hasSizeLessThanOrEqualTo(1);
  }

  @Test
  void a401ClearsTheTokenAndTheCallIsRepeatedOnceWithAFreshOne() {
    var client = clinical();
    client.get().uri("/internal/v1/identity/abc").retrieve().toBodilessEntity();
    String first = CLINICAL_AUTH.get(CLINICAL_AUTH.size() - 1);
    CLINICAL_AUTH.clear();
    clinicalStatus = 401;
    client.get().uri("/internal/v1/identity/abc").retrieve().toBodilessEntity();
    assertThat(CLINICAL_AUTH).hasSize(2);
    assertThat(CLINICAL_AUTH.get(1)).isNotEqualTo(first);
  }

  @Test
  void aRequestThatAlreadyHasAnAuthorizationHeaderIsLeftAlone() {
    clinical()
        .get()
        .uri("/internal/v1/identity/abc")
        .header("Authorization", "Bearer caller-supplied")
        .retrieve()
        .toBodilessEntity();
    assertThat(CLINICAL_AUTH).containsExactly("Bearer caller-supplied");
  }
}
