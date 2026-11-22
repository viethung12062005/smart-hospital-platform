package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * SHR-32 with real Keycloak: {@link ClientCredentialsTokenProvider} gets a service token for a
 * confidential client and the token passes the shared JWT rules ({@code aud=svc-clinical}, the
 * target only) and carries the {@code internal.<service>} role. The realm is a minimal one
 * (identity/realm builds the real {@code svc-*} clients, IAM-02; its own tests check them).
 */
@Testcontainers
class KeycloakServiceTokenTest {
  static final String SECRET = "test-only-service-secret";

  @Container
  static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>(
              DockerImageName.parse(
                  System.getenv()
                      .getOrDefault("SHP_TEST_KEYCLOAK_IMAGE", "quay.io/keycloak/keycloak:26.0")))
          .withCommand("start-dev", "--import-realm", "--http-port=8080")
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "test-only-admin")
          .withEnv("JAVA_OPTS_KC_HEAP", "-XX:MaxRAMPercentage=50")
          .withCopyFileToContainer(
              MountableFile.forHostPath(realmFile()), "/opt/keycloak/data/import/realm-shp.json")
          .withExposedPorts(8080)
          .waitingFor(
              Wait.forHttp("/realms/shp").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));

  static Path realmFile() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null
        && !Files.exists(dir.resolve("src/test/resources/realm-service-token-test.json"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("realm-service-token-test.json not found");
    }
    return dir.resolve("src/test/resources/realm-service-token-test.json");
  }

  static String issuer() {
    return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/shp";
  }

  static ClientCredentialsTokenProvider provider(String secret, Clock clock) {
    return new ClientCredentialsTokenProvider(
        new ServiceTokenProperties(
            issuer() + "/protocol/openid-connect/token", "svc-scheduling", secret, "", null, null),
        clock);
  }

  @Test
  void serviceTokenPassesTheSharedJwtRulesAndCarriesTheInternalRole() {
    String token = provider(SECRET, Clock.systemUTC()).token();
    // The target service (clinical) accepts its own service audience, svc-clinical.
    var props = new ShpAuthProperties(issuer(), "svc-clinical", true, Duration.ofSeconds(30), "");
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(issuer() + "/protocol/openid-connect/certs").build();
    decoder.setJwtValidator(ShpJwt.validator(props, Clock.systemUTC()));
    Jwt jwt = decoder.decode(token);
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("internal.clinical");
    assertThat(jwt.getAudience()).contains("svc-clinical");
    // A service token never carries shp-api: that audience belongs to user tokens (ADR-0017).
    assertThat(jwt.getAudience()).doesNotContain(ShpJwt.SHARED_AUDIENCE);
    assertThat(jwt.getClaimAsString("azp")).isEqualTo("svc-scheduling");
    // The shared /internal guard accepts this caller for clinical and refuses it for another
    // service.
    ShpJwt.requireService(jwt, "clinical", "svc-scheduling");
    assertThat(ShpJwt.isServiceToken(jwt, "scheduling", "svc-scheduling")).isFalse();
    // A service has no user role, so user endpoints refuse it (15 section 7)
    assertThat(ShpJwt.roles(jwt)).isEmpty();
  }

  @Test
  void tokenIsCachedThenRefreshedBeforeItExpires() {
    var movable = new ClientCredentialsTokenProviderTest.MovableClock();
    movable.now = Instant.now();
    var p = provider(SECRET, movable);
    String first = p.token();
    assertThat(p.token()).isEqualTo(first);
    // realm lifespan 60 s, refresh 30 s early
    movable.now = movable.now.plusSeconds(31);
    String second = p.token();
    assertThat(second).isNotEqualTo(first);
    assertThat(p.token()).isEqualTo(second);
  }

  @Test
  void wrongSecretIsRefusedWithoutLeakingIt() {
    assertThatThrownBy(() -> provider("not-the-secret", Clock.systemUTC()).token())
        .isInstanceOf(ServiceTokenException.class)
        .hasMessageContaining("401")
        .hasMessageNotContaining("not-the-secret");
  }
}
