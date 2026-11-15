package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * SHR-20 with real Keycloak: tokens issued by the {@code shp} realm of the local cluster
 * (dev/chart/shp-local-deps) must pass exactly the rules of {@link ShpJwt}. Using the cluster's
 * real realm file exposes configuration drift (roles mapper, audience) here rather than when a dev
 * logs in.
 */
@Testcontainers
class KeycloakTokenTest {
  static final String ADMIN_PASSWORD = "test-only-admin";
  static final String USER_PASSWORD = "Test-only-password-1";

  @Container
  static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>(
              DockerImageName.parse(
                  System.getenv()
                      .getOrDefault("SHP_TEST_KEYCLOAK_IMAGE", "quay.io/keycloak/keycloak:26.0")))
          .withCommand("start-dev", "--import-realm", "--http-port=8080")
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
          .withEnv("JAVA_OPTS_KC_HEAP", "-XX:MaxRAMPercentage=50")
          .withCopyFileToContainer(
              MountableFile.forHostPath(realmFile()), "/opt/keycloak/data/import/realm-shp.json")
          .withExposedPorts(8080)
          .waitingFor(
              Wait.forHttp("/realms/shp").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

  static final HttpClient HTTP = HttpClient.newHttpClient();
  static String base;
  static String issuer;

  static Path realmFile() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null
        && !Files.exists(dir.resolve("dev/chart/shp-local-deps/files/realm-shp.json"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("dev/chart/shp-local-deps/files/realm-shp.json not found");
    }
    return dir.resolve("dev/chart/shp-local-deps/files/realm-shp.json");
  }

  @BeforeAll
  static void seedUsers() throws Exception {
    base = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    issuer = base + "/realms/shp";
    String admin =
        field(
            form(
                base + "/realms/master/protocol/openid-connect/token",
                "grant_type=password&client_id=admin-cli&username=admin&password="
                    + ADMIN_PASSWORD),
            "access_token");
    createUser(admin, "patient.it", "patient");
    createUser(admin, "doctor.it", "doctor");
  }

  static void createUser(String admin, String username, String role) throws Exception {
    String user =
        """
        {"username":"%s","enabled":true,"emailVerified":true,"email":"%s@example.test",
         "firstName":"Test","lastName":"User",
         "credentials":[{"type":"password","value":"%s","temporary":false}]}
        """
            .formatted(username, username, USER_PASSWORD);
    var created = json("POST", base + "/admin/realms/shp/users", admin, user);
    assertThat(created.statusCode()).isEqualTo(201);
    String id = created.headers().firstValue("Location").orElseThrow().replaceAll(".*/", "");
    String roleJson = json("GET", base + "/admin/realms/shp/roles/" + role, admin, null).body();
    var mapped =
        json(
            "POST",
            base + "/admin/realms/shp/users/" + id + "/role-mappings/realm",
            admin,
            "[" + roleJson + "]");
    assertThat(mapped.statusCode()).isEqualTo(204);
  }

  static HttpResponse<String> json(String method, String url, String bearer, String body)
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

  static String form(String url, String body) throws Exception {
    var req =
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    var res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    return res.body();
  }

  static String field(String json, String name) {
    var m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
    if (!m.find()) {
      throw new IllegalStateException("Missing " + name);
    }
    return m.group(1);
  }

  /** Real token via password grant of the test client {@code shp-test} (same as `make token`). */
  static String token(String username) throws Exception {
    return field(
        form(
            issuer + "/protocol/openid-connect/token",
            "grant_type=password&client_id=shp-test&username="
                + username
                + "&password="
                + URLEncoder.encode(USER_PASSWORD, StandardCharsets.UTF_8)),
        "access_token");
  }

  static JwtDecoder decoder(String audience, boolean requireMfa, Clock clock) {
    var props = new ShpAuthProperties(issuer, audience, requireMfa, Duration.ofSeconds(30), "");
    NimbusJwtDecoder d =
        NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
    d.setJwtValidator(ShpJwt.validator(props, clock));
    return d;
  }

  @Test
  void validPatientTokenIsAcceptedAndRolesAreMapped() throws Exception {
    Jwt jwt = decoder("shp-api", true, Clock.systemUTC()).decode(token("patient.it"));
    assertThat(jwt.getIssuer().toString()).isEqualTo(issuer);
    assertThat(jwt.getAudience()).contains("shp-api");
    assertThat(jwt.getSubject()).isNotBlank();
    // Keycloak adds default roles (offline_access, default-roles-shp...): keep only SHP roles
    assertThat(ShpJwt.roles(jwt)).containsExactly("patient");
    var auth = ShpJwt.authenticationConverter().convert(jwt);
    assertThat(auth.getName()).isEqualTo(jwt.getSubject());
    // Spring Security 7 adds FACTOR_* authorities automatically (authentication method); business
    // roles include only patient
    assertThat(auth.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .filteredOn(a -> !a.startsWith("FACTOR_"))
        .containsExactly("patient");
    // 07 §2: access tokens live at most 15 minutes
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
        .isLessThanOrEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void tamperedSignatureIsRejected() throws Exception {
    String t = token("patient.it");
    String sig = t.substring(t.lastIndexOf('.') + 1);
    String flipped = (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1);
    String tampered = t.substring(0, t.lastIndexOf('.') + 1) + flipped;
    assertThatThrownBy(() -> decoder("shp-api", true, Clock.systemUTC()).decode(tampered))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void expiredTokenIsRejected() throws Exception {
    String t = token("patient.it");
    // 16 minutes later: past the 15-minute expiry + 30-second skew
    Clock later = Clock.fixed(Instant.now().plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
    assertThatThrownBy(() -> decoder("shp-api", true, later).decode(t))
        .isInstanceOf(JwtValidationException.class);
  }

  @Test
  void wrongAudienceOrIssuerIsRejected() throws Exception {
    String t = token("patient.it");
    assertThatThrownBy(() -> decoder("other-api", true, Clock.systemUTC()).decode(t))
        .isInstanceOf(JwtValidationException.class);
    var otherIssuer =
        new ShpAuthProperties(
            "https://idp.example.test/realms/shp", "shp-api", true, Duration.ofSeconds(30), "");
    NimbusJwtDecoder d =
        NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
    d.setJwtValidator(ShpJwt.validator(otherIssuer, Clock.systemUTC()));
    assertThatThrownBy(() -> d.decode(t)).isInstanceOf(JwtValidationException.class);
  }

  @Test
  void staffWithoutMfaIsRejectedUnlessMfaIsSwitchedOff() throws Exception {
    String t = token("doctor.it");
    // The local realm has no MFA configured, so staff tokens have no amr=mfa (US-008)
    assertThatThrownBy(() -> decoder("shp-api", true, Clock.systemUTC()).decode(t))
        .isInstanceOfSatisfying(
            JwtValidationException.class,
            e ->
                assertThat(e.getErrors())
                    .anySatisfy(err -> assertThat(err.getDescription()).isEqualTo("mfa-required")));
    // The local cluster sets SHP_REQUIRE_MFA_FOR_STAFF=false so devs can log in
    Jwt jwt = decoder("shp-api", false, Clock.systemUTC()).decode(t);
    assertThat(ShpJwt.roles(jwt)).containsExactly("doctor");
  }
}
