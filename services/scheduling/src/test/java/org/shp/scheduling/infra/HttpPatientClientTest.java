package org.shp.scheduling.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.shp.security.ServiceTokenProvider;
import org.shp.web.auth.ServiceTokenInterceptor;
import org.shp.web.errors.ShpException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The real {@link HttpPatientClient} against a local stub of the clinical age-band route. */
class HttpPatientClientTest {
  private static final String SERVICE_TOKEN = "stub-service-token";

  private HttpServer server;
  private HttpPatientClient client;
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicInteger delayMillis = new AtomicInteger();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          if (delayMillis.get() > 0) {
            try {
              Thread.sleep(delayMillis.get());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] body = "{\"ageBand\":\"40-49\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    ServiceTokenProvider tokens =
        new ServiceTokenProvider() {
          @Override
          public String token(String target) {
            return SERVICE_TOKEN;
          }

          @Override
          public void invalidate(String target) {}
        };
    // Built like ClinicalClientConfig: base URL, JDK client, service token interceptor.
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    requestFactory.setReadTimeout(Duration.ofMillis(500));
    client =
        new HttpPatientClient(
            RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(requestFactory)
                .requestInterceptor(new ServiceTokenInterceptor(tokens, "clinical"))
                .build());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  @DisplayName("200 means the patient exists; the call is authenticated as the service")
  void existingPatient() {
    UUID id = UUID.randomUUID();

    assertThat(client.exists(id)).isTrue();
    assertThat(path.get()).isEqualTo("/internal/v1/patients/" + id + "/age-band");
    assertThat(authorization.get()).isEqualTo("Bearer " + SERVICE_TOKEN);
  }

  @Test
  @DisplayName("404 means the patient does not exist")
  void missingPatient() {
    status.set(404);
    assertThat(client.exists(UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName("a 5xx answer is dependency-unavailable")
  void serverError() {
    status.set(503);
    assertThatThrownBy(() -> client.exists(UUID.randomUUID()))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> assertThat(e.info().code()).isEqualTo("dependency-unavailable"));
  }

  @Test
  @DisplayName("a timeout is dependency-unavailable")
  void timeout() {
    delayMillis.set(1500);
    assertThatThrownBy(() -> client.exists(UUID.randomUUID()))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> assertThat(e.info().code()).isEqualTo("dependency-unavailable"));
  }
}
