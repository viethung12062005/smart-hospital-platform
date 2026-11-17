package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Helpers for tests that run a real Keycloak (IAM-01, IAM-02, IAM-06, IAM-07). The image is pinned
 * to the version the cluster runs (shp-gitops platform/charts/shp-keycloak) so a feature that
 * exists only in a newer Keycloak, such as Kubernetes service-account client authentication, is
 * tested where it is deployed.
 */
final class KeycloakTestSupport {
  static final String IMAGE =
      System.getenv().getOrDefault("SHP_TEST_KEYCLOAK_IMAGE", "quay.io/keycloak/keycloak:26.8.2");
  static final String ADMIN_PASSWORD = "test-only-admin";
  static final HttpClient HTTP = HttpClient.newHttpClient();

  private KeycloakTestSupport() {}

  /** A started-on-demand container that imports the given realm file from the repository. */
  static GenericContainer<?> container(String realmFileInRepo, String... envPairs) {
    var c =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withCommand("start-dev", "--import-realm", "--http-port=8080")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("JAVA_OPTS_KC_HEAP", "-XX:MaxRAMPercentage=50")
            .withCopyFileToContainer(
                MountableFile.forHostPath(repoFile(realmFileInRepo)),
                "/opt/keycloak/data/import/realm-shp.json")
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/realms/shp")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));
    for (int i = 0; i + 1 < envPairs.length; i += 2) {
      c.withEnv(envPairs[i], envPairs[i + 1]);
    }
    return c;
  }

  static Path repoFile(String relative) {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve(relative))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException(
          relative + " not found above " + Path.of("").toAbsolutePath());
    }
    return dir.resolve(relative);
  }

  static String base(GenericContainer<?> kc) {
    return "http://" + kc.getHost() + ":" + kc.getMappedPort(8080);
  }

  static String adminToken(String base) throws Exception {
    return JsonPath.read(
        form(
            base + "/realms/master/protocol/openid-connect/token",
            "grant_type=password&client_id=admin-cli&username=admin&password=" + ADMIN_PASSWORD),
        "$.access_token");
  }

  /** Claims of a JWT (payload only; the signature is not checked here). */
  static com.jayway.jsonpath.DocumentContext claims(String jwt) {
    String payload = jwt.split("\\.")[1];
    return JsonPath.parse(new String(java.util.Base64.getUrlDecoder().decode(payload)));
  }

  static String form(String url, String body) throws Exception {
    var res = formResponse(url, body);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    return res.body();
  }

  static HttpResponse<String> formResponse(String url, String body) throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
  }

  static HttpResponse<String> admin(String method, String url, String bearer, String body)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + bearer)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  static String adminGet(String url, String bearer) throws Exception {
    var res = admin("GET", url, bearer, null);
    assertThat(res.statusCode()).as(url + " " + res.body()).isEqualTo(200);
    return res.body();
  }

  /** GET that does not follow redirects, for authorization-endpoint checks. */
  static HttpResponse<String> getNoRedirect(String url) throws Exception {
    var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
