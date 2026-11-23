package org.shp.scheduling.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.web.errors.ShpException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class HttpPractitionerRoleClientTest {

  private HttpServer server;
  private HttpPractitionerRoleClient client;
  private final AtomicReference<StubResponse> nextResponse = new AtomicReference<>();
  private final AtomicReference<String> lastPath = new AtomicReference<>();
  private final AtomicInteger requestCount = new AtomicInteger();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestCount.incrementAndGet();
          lastPath.set(exchange.getRequestURI().getPath());
          StubResponse stub = nextResponse.get();
          byte[] bytes = stub.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(stub.status(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();

    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    requestFactory.setReadTimeout(Duration.ofSeconds(2));
    client =
        new HttpPractitionerRoleClient(
            RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build());
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("filters inactive/blank roles, normalizes case, and deduplicates specialties")
  void filtersInactiveAndNormalizesSpecialties() {
    UUID practitionerId = UUID.randomUUID();
    nextResponse.set(
        new StubResponse(
            200,
            """
            [
              {"id":"r1","specialty":" Cardiology ","code":"doctor","active":true},
              {"id":"r2","specialty":"dermatology","code":"doctor","active":false},
              {"id":"r3","specialty":"CARDIOLOGY","code":"doctor","active":true},
              {"id":"r4","specialty":"   ","code":"doctor","active":true},
              {"id":"r5","specialty":null,"code":"doctor","active":true},
              {"id":"r6","specialty":"Pediatrics","code":"doctor","active":true}
            ]
            """));

    List<String> result = client.findActiveSpecialties(practitionerId);

    assertThat(lastPath.get()).isEqualTo("/internal/v1/practitioners/" + practitionerId + "/roles");
    assertThat(result).containsExactly("cardiology", "pediatrics");
    assertThat(client.hasActiveSpecialty(practitionerId, "Cardiology")).isTrue();
    assertThat(client.hasActiveSpecialty(practitionerId, "dermatology")).isFalse();
  }

  @Test
  @DisplayName("returns empty list for 200 empty array and 404 unknown practitioner")
  void returnsEmptyOnEmptyArrayAnd404() {
    UUID practitionerId = UUID.randomUUID();

    nextResponse.set(new StubResponse(200, "[]"));
    assertThat(client.findActiveSpecialties(practitionerId)).isEmpty();

    nextResponse.set(new StubResponse(404, "{}"));
    assertThat(client.findActiveSpecialties(practitionerId)).isEmpty();
    assertThat(client.hasActiveSpecialty(practitionerId, "cardiology")).isFalse();
  }

  @Test
  @DisplayName("returns empty list without making an HTTP call when practitionerId is null")
  void nullPractitionerReturnsEmptyWithoutHttpCall() {
    assertThat(client.findActiveSpecialties(null)).isEmpty();
    assertThat(requestCount.get()).isZero();
  }

  @Test
  @DisplayName("throws ShpException(dependency-unavailable) on 401/403/500/503 responses")
  void throwsDependencyUnavailableOnNon404Errors() {
    UUID practitionerId = UUID.randomUUID();
    for (int status : List.of(401, 403, 500, 503)) {
      nextResponse.set(new StubResponse(status, "{\"error\":\"upstream\"}"));
      assertThatThrownBy(() -> client.findActiveSpecialties(practitionerId))
          .isInstanceOf(ShpException.class)
          .satisfies(ex -> {
            var shp = (ShpException) ex;
            assertThat(shp.info().code()).isEqualTo("dependency-unavailable");
            assertThat(shp.info().status()).isEqualTo(503);
          });
    }
  }

  @Test
  @DisplayName(
      "throws ShpException(dependency-unavailable) on malformed JSON or connection failure")
  void throwsDependencyUnavailableOnTransportOrJsonFailure() throws IOException {
    UUID practitionerId = UUID.randomUUID();
    nextResponse.set(new StubResponse(200, "{not-valid-json"));
    assertThatThrownBy(() -> client.findActiveSpecialties(practitionerId))
        .isInstanceOf(ShpException.class)
        .satisfies(ex ->
            assertThat(((ShpException) ex).info().code()).isEqualTo("dependency-unavailable"));

    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    var unreachableClient =
        new HttpPractitionerRoleClient(
            RestClient.builder().baseUrl("http://127.0.0.1:" + closedPort).build());

    assertThatThrownBy(() -> unreachableClient.findActiveSpecialties(practitionerId))
        .isInstanceOf(ShpException.class)
        .satisfies(ex ->
            assertThat(((ShpException) ex).info().code()).isEqualTo("dependency-unavailable"));
  }

  private record StubResponse(int status, String body) {}
}
