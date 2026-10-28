package org.shp.servicedefaults;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.application.name=demo", "GIT_SHA=abc123"})
class ServiceDefaultsTest {

  @SpringBootApplication
  static class App {}

  @Autowired Environment env;

  private HttpResponse<String> get(String path) throws Exception {
    var uri = URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    try (var client = HttpClient.newHttpClient()) {
      return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  @Test
  void liveAndReadyProbesAreUpAtChartPaths() throws Exception {
    for (String path : new String[] {"/health/live", "/health/ready", "/health"}) {
      var r = get(path);
      assertThat(r.statusCode()).as(path).isEqualTo(200);
      assertThat(r.body()).contains("UP");
    }
  }

  @Test
  void infoReportsNameVersionAndGitSha() throws Exception {
    var r = get("/info");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body())
        .contains("\"name\":\"demo\"")
        .contains("\"gitSha\":\"abc123\"")
        .contains("\"version\":\"dev\"");
  }

  @Test
  void prometheusMetricsAreExposed() throws Exception {
    var r = get("/prometheus");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("jvm_memory_used_bytes");
  }

  @Test
  void healthDoesNotLeakDetails() throws Exception {
    assertThat(get("/health").body()).doesNotContain("diskSpace");
  }

  @Test
  void otlpExportIsOffUnlessClusterEnablesIt() {
    assertThat(env.getProperty("management.tracing.export.otlp.enabled")).isEqualTo("false");
    assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
  }
}
