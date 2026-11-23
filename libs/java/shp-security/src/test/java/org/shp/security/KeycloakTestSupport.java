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
  static final String SMTP_HOST = "smtp.example.test";
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
            .withCopyFileToContainer(
                MountableFile.forHostPath(repoFile("identity/realm/breached-passwords.txt")),
                "/opt/keycloak/data/password-blacklists/breached-passwords.txt")
            // The cluster realm takes its mail server from these variables (IAM-03) and Keycloak
            // validates the sender address on import. Synthetic values: nothing is sent in these
            // tests.
            .withEnv("SHP_SMTP_HOST", SMTP_HOST)
            .withEnv("SHP_SMTP_PORT", "587")
            .withEnv("SHP_SMTP_FROM", "no-reply@example.test")
            .withEnv("SHP_SMTP_USER", "shp-mail")
            .withEnv("SHP_SMTP_PASSWORD", "test-only-smtp")
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

  /**
   * A synthetic TOTP seed, TEST FIXTURES ONLY (never in a realm file). Keycloak stores the seed as
   * a raw string and uses its bytes as the HMAC key, so the same string generates the codes here.
   */
  static final String TEST_TOTP_SEED = "synthetic-totp-seed-0001";

  static final long TOTP_STEP_MS = 30_000;

  /** A code must still be valid this long after it is computed (hashing, network, retries). */
  static final long TOTP_SAFE_MS = 10_000;

  /**
   * Milliseconds to wait before a code computed at {@code nowMs} is safe to submit. The realm
   * accepts only the code of the current 30-second step (no look-behind or look-ahead window), so a
   * code computed shortly before a step boundary is rejected by the server as {@code
   * invalid_grant}. Returns 0 when at least {@link #TOTP_SAFE_MS} of the step remain, otherwise the
   * time to the start of the next step.
   */
  static long totpWaitMillis(long nowMs) {
    long remaining = TOTP_STEP_MS - (nowMs % TOTP_STEP_MS);
    return remaining >= TOTP_SAFE_MS ? 0 : remaining;
  }

  /**
   * RFC 6238 code (HMAC-SHA1, 6 digits, 30 s) for the given seed, computed in a step that has
   * enough time left to be submitted (see {@link #totpWaitMillis}).
   */
  static String totp(String seed) throws Exception {
    long wait = totpWaitMillis(System.currentTimeMillis());
    if (wait > 0) {
      Thread.sleep(wait);
    }
    long counter = System.currentTimeMillis() / 1000 / 30;
    var mac = javax.crypto.Mac.getInstance("HmacSHA1");
    mac.init(
        new javax.crypto.spec.SecretKeySpec(
            seed.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA1"));
    byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(counter).array());
    int o = h[h.length - 1] & 0x0f;
    int bin =
        ((h[o] & 0x7f) << 24)
            | ((h[o + 1] & 0xff) << 16)
            | ((h[o + 2] & 0xff) << 8)
            | (h[o + 3] & 0xff);
    return String.format("%06d", bin % 1_000_000);
  }

  /**
   * Creates a public test client with the password and browser flows plus the claims the services
   * read ({@code roles}, {@code aud=shp-api}, {@code amr}). The prod realm has no {@code shp-test}
   * (only the local one does), and its {@code shp-web} accepts only the public host as a redirect,
   * so a test that needs a sign-in against the prod realm creates this client first. Test-only:
   * nothing like it is in a committed realm.
   */
  static void createTestClient(String base, String admin, String clientId, String redirect)
      throws Exception {
    String body =
        """
        {"clientId":"%s","name":"Test only: password and browser login","enabled":true,
         "publicClient":true,"standardFlowEnabled":true,"directAccessGrantsEnabled":true,
         "implicitFlowEnabled":false,"redirectUris":["%s"],"webOrigins":["+"],
         "attributes":{"pkce.code.challenge.method":"S256"},
         "defaultClientScopes":["basic","profile","email"],
         "protocolMappers":[
           {"name":"roles-claim","protocol":"openid-connect",
            "protocolMapper":"oidc-usermodel-realm-role-mapper",
            "config":{"claim.name":"roles","jsonType.label":"String","multivalued":"true",
                      "access.token.claim":"true","id.token.claim":"true","userinfo.token.claim":"true"}},
           {"name":"audience-shp-api","protocol":"openid-connect",
            "protocolMapper":"oidc-audience-mapper",
            "config":{"included.client.audience":"shp-api","id.token.claim":"false",
                      "access.token.claim":"true"}},
           {"name":"amr-claim","protocol":"openid-connect","protocolMapper":"oidc-amr-mapper",
            "config":{"id.token.claim":"true","access.token.claim":"true"}}]}
        """
            .formatted(clientId, redirect);
    var created = admin("POST", base + "/admin/realms/shp/clients", admin, body);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
  }

  /**
   * Creates a user with a role, a password and optionally an enrolled TOTP credential (the same
   * representation Keycloak writes when a user scans the QR code) and returns its id.
   */
  static String createUser(
      String base, String admin, String username, String role, String password, String totpSeed)
      throws Exception {
    String otp =
        totpSeed == null
            ? ""
            : """
            ,{"type":"otp","secretData":"{\\"value\\":\\"%s\\"}",
              "credentialData":"{\\"subType\\":\\"totp\\",\\"digits\\":6,\\"period\\":30,\\"algorithm\\":\\"HmacSHA1\\",\\"counter\\":0}"}
            """
                .formatted(totpSeed);
    String body =
        """
        {"username":"%s","enabled":true,"emailVerified":true,"email":"%s@example.test",
         "firstName":"Test","lastName":"User",
         "credentials":[{"type":"password","value":"%s","temporary":false}%s]}
        """
            .formatted(username, username, password, otp);
    var created = admin("POST", base + "/admin/realms/shp/users", admin, body);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    String id = created.headers().firstValue("Location").orElseThrow().replaceAll(".*/", "");
    String roleJson = adminGet(base + "/admin/realms/shp/roles/" + role, admin);
    var mapped =
        admin(
            "POST",
            base + "/admin/realms/shp/users/" + id + "/role-mappings/realm",
            admin,
            "[" + roleJson + "]");
    assertThat(mapped.statusCode()).isEqualTo(204);
    return id;
  }
}
