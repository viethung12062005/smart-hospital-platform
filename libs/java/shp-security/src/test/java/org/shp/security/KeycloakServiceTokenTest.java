package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SHR-32 with real Keycloak: {@link ClientCredentialsTokenProvider} gets a service token for a
 * confidential client and the token passes the shared JWT rules ({@code aud=svc-clinical}, the
 * target only) and carries the {@code internal.<service>} role. Runs against the generated local
 * realm so the application client and Keycloak's target-selection configuration are tested
 * together.
 */
@Testcontainers
class KeycloakServiceTokenTest {
  @Container
  static final GenericContainer<?> KEYCLOAK =
      KeycloakTestSupport.container("dev/chart/shp-local-deps/files/realm-shp.json");

  static String clientSecret;

  @BeforeAll
  static void readClientSecret() throws Exception {
    String base = KeycloakTestSupport.base(KEYCLOAK);
    String admin = KeycloakTestSupport.adminToken(base);
    String clients = base + "/admin/realms/shp/clients";
    String id =
        JsonPath.read(
            KeycloakTestSupport.adminGet(clients + "?clientId=svc-scheduling", admin), "$[0].id");
    clientSecret =
        JsonPath.read(
            KeycloakTestSupport.adminGet(clients + "/" + id + "/client-secret", admin), "$.value");
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
    String token = provider(clientSecret, Clock.systemUTC()).token("clinical");
    // The target service (clinical) accepts its own service audience, svc-clinical.
    var props = new ShpAuthProperties(issuer(), "svc-clinical", true, Duration.ofSeconds(30), "");
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(issuer() + "/protocol/openid-connect/certs").build();
    decoder.setJwtValidator(ShpJwt.validator(props, Clock.systemUTC()));
    Jwt jwt = decoder.decode(token);
    assertThat(jwt.getClaimAsStringList("roles")).contains("internal.clinical");
    assertThat(jwt.getAudience()).containsExactly("svc-clinical");
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
    var p = provider(clientSecret, movable);
    String first = p.token("clinical");
    assertThat(p.token("clinical")).isEqualTo(first);
    // realm lifespan 900 s, refresh 30 s early
    movable.now = movable.now.plusSeconds(875);
    String second = p.token("clinical");
    assertThat(second).isNotEqualTo(first);
    assertThat(p.token("clinical")).isEqualTo(second);
  }

  @Test
  void wrongSecretIsRefusedWithoutLeakingIt() {
    assertThatThrownBy(() -> provider("not-the-secret", Clock.systemUTC()).token("clinical"))
        .isInstanceOf(ServiceTokenException.class)
        .hasMessageContaining("401")
        .hasMessageNotContaining("not-the-secret");
  }
}
