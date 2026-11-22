package org.shp.identity.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import org.jacoco.agent.rt.RT;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A real Keycloak 26.8.2 with the provider JAR this module builds ({@code target/shp-keycloak.jar},
 * the file the image copies), the local realm generated from {@code identity/realm}, and a Mailpit
 * mail catcher under the host name the local realm sends to ({@code mailpit}). Integration tests
 * only: they run after {@code package}.
 */
final class KeycloakEnvironment implements AutoCloseable {
  private static final Object COVERAGE_LOCK = new Object();
  private static final String CONTAINER_COVERAGE = "/opt/keycloak/data/shp-keycloak-jacoco.exec";
  static final String IMAGE =
      System.getenv().getOrDefault("SHP_TEST_KEYCLOAK_IMAGE", "quay.io/keycloak/keycloak:26.8.2");
  static final String MAILPIT_IMAGE = "axllent/mailpit:v1.33.1";
  static final String ADMIN_PASSWORD = "test-only-admin";
  static final HttpClient HTTP = HttpClient.newHttpClient();

  final Network network = Network.newNetwork();
  final GenericContainer<?> mailpit =
      new GenericContainer<>(DockerImageName.parse(MAILPIT_IMAGE))
          .withNetwork(network)
          .withNetworkAliases("mailpit")
          .withExposedPorts(8025)
          .waitingFor(Wait.forHttp("/readyz").forPort(8025));
  final GenericContainer<?> keycloak;

  KeycloakEnvironment() {
    keycloak =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withNetwork(network)
            .withCommand("start-dev", "--import-realm", "--http-port=8080")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("JAVA_OPTS_KC_HEAP", "-XX:MaxRAMPercentage=50")
            .withEnv(
                "JAVA_OPTS_APPEND",
                "-javaagent:/opt/keycloak/jacocoagent.jar=destfile="
                    + CONTAINER_COVERAGE
                    + ",append=false")
            .withCopyFileToContainer(
                MountableFile.forHostPath(coverageAgent()), "/opt/keycloak/jacocoagent.jar")
            .withCopyFileToContainer(
                MountableFile.forHostPath(providerJar()),
                "/opt/keycloak/providers/shp-keycloak.jar")
            .withCopyFileToContainer(
                MountableFile.forHostPath(testOtpJar()),
                "/opt/keycloak/providers/shp-keycloak-test-otp.jar")
            .withCopyFileToContainer(
                MountableFile.forHostPath(
                    repoFile("dev/chart/shp-local-deps/files/realm-shp.json")),
                "/opt/keycloak/data/import/realm-shp.json")
            .withCopyFileToContainer(
                MountableFile.forHostPath(repoFile("identity/realm/breached-passwords.txt")),
                "/opt/keycloak/data/password-blacklists/breached-passwords.txt")
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/realms/shp")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));
  }

  KeycloakEnvironment start() {
    mailpit.start();
    keycloak.start();
    return this;
  }

  @Override
  public void close() {
    try {
      if (keycloak.isRunning()) {
        // A graceful JVM stop makes the agent write its execution data. Docker can copy a file
        // from the stopped container before Testcontainers removes it.
        keycloak
            .getDockerClient()
            .stopContainerCmd(keycloak.getContainerId())
            .withTimeout(30)
            .exec();
        appendContainerCoverage();
      }
    } finally {
      keycloak.stop();
      mailpit.stop();
      network.close();
    }
  }

  String base() {
    return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
  }

  String issuer() {
    return base() + "/realms/shp";
  }

  String mailApi() {
    return "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + "/api/v1";
  }

  static Path providerJar() {
    Path jar = Path.of("target", "shp-keycloak.jar").toAbsolutePath();
    if (!Files.exists(jar)) {
      throw new IllegalStateException(jar + " missing: integration tests run after `mvn package`");
    }
    return jar;
  }

  /** The JaCoCo runtime JAR on the test class path, copied into the disposable Keycloak JVM. */
  static Path coverageAgent() {
    try {
      return Path.of(RT.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (Exception e) {
      throw new IllegalStateException("cannot locate the JaCoCo runtime agent", e);
    }
  }

  private void appendContainerCoverage() {
    byte[] executionData =
        keycloak.copyFileFromContainer(CONTAINER_COVERAGE, in -> in.readAllBytes());
    synchronized (COVERAGE_LOCK) {
      try {
        Files.write(
            Path.of("target", "jacoco.exec"),
            executionData,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (java.io.IOException e) {
        throw new IllegalStateException("cannot preserve Keycloak coverage", e);
      }
    }
  }

  /** The test-only OTP provider (testotp package), built next to the provider JAR. */
  static Path testOtpJar() {
    Path jar = Path.of("target", "shp-keycloak-test-otp.jar").toAbsolutePath();
    if (!Files.exists(jar)) {
      throw new IllegalStateException(jar + " missing: integration tests run after `mvn package`");
    }
    return jar;
  }

  /** The last code the test OTP provider sent to a destination: "purpose channel locale code". */
  String lastCode(String destination) {
    return keycloak.copyFileFromContainer(
        "/opt/keycloak/data/test-otp/" + enc(destination),
        in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));
  }

  /**
   * Turns sign-up on, as the realm will once the notification-backed code provider ships (IAM-04).
   */
  void allowRegistration() throws Exception {
    var res = admin("PUT", "", "{\"registrationAllowed\":true}");
    assertThat(res.statusCode()).as(res.body()).isEqualTo(204);
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

  String adminToken() throws Exception {
    var res =
        post(
            base() + "/realms/master/protocol/openid-connect/token",
            "grant_type=password&client_id=admin-cli&username=admin&password=" + ADMIN_PASSWORD);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    return JsonPath.read(res.body(), "$.access_token");
  }

  HttpResponse<String> admin(String method, String path, String body) throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create(base() + "/admin/realms/shp" + path))
            .header("Authorization", "Bearer " + adminToken())
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Creates an enabled patient-like user with a verified email and a password; returns its id. */
  String createUser(String username, String email, String password) throws Exception {
    String body =
        """
        {"username":"%s","enabled":true,"emailVerified":true,"email":"%s",
         "firstName":"Test","lastName":"User",
         "credentials":[{"type":"password","value":"%s","temporary":false}]}
        """
            .formatted(username, email, password);
    var created = admin("POST", "/users", body);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    return created.headers().firstValue("Location").orElseThrow().replaceAll(".*/", "");
  }

  /** Messages Mailpit received, as its JSON listing. */
  String mails() throws Exception {
    var res =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(mailApi() + "/messages")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(200);
    return res.body();
  }

  static HttpResponse<String> post(String url, String form) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
