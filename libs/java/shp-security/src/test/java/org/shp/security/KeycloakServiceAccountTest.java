package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IAM-02: service accounts for {@code /internal} against a real Keycloak (local realm: client
 * secret). Every service client in identity/realm/services.json gets exactly the roles of the
 * services it may call, an audience made of its targets only ({@code svc-<target>}, never {@code
 * shp-api}; ADR-0017), and can do nothing else.
 */
@Testcontainers
class KeycloakServiceAccountTest {
  @Container
  static final GenericContainer<?> KEYCLOAK =
      KeycloakTestSupport.container("dev/chart/shp-local-deps/files/realm-shp.json");

  static String enc(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
  }

  static String secretOf(String clientId) throws Exception {
    String base = KeycloakTestSupport.base(KEYCLOAK);
    String admin = KeycloakTestSupport.adminToken(base);
    String id =
        JsonPath.read(
            KeycloakTestSupport.adminGet(
                base + "/admin/realms/shp/clients?clientId=" + clientId, admin),
            "$[0].id");
    return JsonPath.read(
        KeycloakTestSupport.adminGet(
            base + "/admin/realms/shp/clients/" + id + "/client-secret", admin),
        "$.value");
  }

  static String tokenUrl() {
    return KeycloakTestSupport.base(KEYCLOAK) + "/realms/shp/protocol/openid-connect/token";
  }

  static String serviceToken(String clientId) throws Exception {
    return JsonPath.read(
        KeycloakTestSupport.form(
            tokenUrl(),
            "grant_type=client_credentials&client_id="
                + clientId
                + "&client_secret="
                + enc(secretOf(clientId))),
        "$.access_token");
  }

  @Test
  void everyServiceGetsExactlyTheRolesAndAudiencesOfItsTargets() throws Exception {
    String spec =
        java.nio.file.Files.readString(
            KeycloakTestSupport.repoFile("identity/realm/services.json"));
    Map<String, Map<String, List<String>>> services = JsonPath.read(spec, "$.services");
    assertThat(services).isNotEmpty();
    for (var entry : services.entrySet()) {
      List<String> calls = entry.getValue().get("calls");
      // exchange targets are audiences too: Keycloak only exchanges into an available audience
      List<String> exchange = entry.getValue().getOrDefault("exchange", java.util.List.of());
      var claims = KeycloakTestSupport.claims(serviceToken("svc-" + entry.getKey()));
      List<String> roles = claims.read("$.roles");
      List<String> internal = roles.stream().filter(r -> r.startsWith("internal.")).toList();
      assertThat(internal)
          .as("internal roles of " + entry.getKey())
          .containsExactlyInAnyOrderElementsOf(calls.stream().map(c -> "internal." + c).toList());
      assertThat(claims.<String>read("$.azp")).isEqualTo("svc-" + entry.getKey());
      Object aud = claims.read("$.aud");
      List<String> audiences =
          aud instanceof List<?> l
              ? l.stream().map(String::valueOf).toList()
              : List.of(String.valueOf(aud));
      // audience isolation: the token names the targets (and exchange targets) and nothing else
      assertThat(audiences)
          .containsExactlyInAnyOrderElementsOf(
              java.util.stream.Stream.concat(calls.stream(), exchange.stream())
                  .distinct()
                  .map(c -> "svc-" + c)
                  .toList())
          .doesNotContain("shp-api");
    }
  }

  @Test
  void serviceTokenPassesTheSharedJwtRulesButCarriesNoUserRole() throws Exception {
    String token = serviceToken("svc-notification");
    String issuer = KeycloakTestSupport.base(KEYCLOAK) + "/realms/shp";
    // notification may call clinical: its audience names svc-clinical only
    var props =
        new ShpAuthProperties(issuer, "svc-clinical", true, java.time.Duration.ofSeconds(30), "");
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
    decoder.setJwtValidator(ShpJwt.validator(props, Clock.systemUTC()));
    Jwt jwt = decoder.decode(token);
    // Layer 2 (15 section 1) keys on patient/doctor/nurse/admin/dpo: a service has none, so user
    // endpoints refuse it.
    assertThat(ShpJwt.roles(jwt)).isEmpty();
    assertThat(jwt.getClaimAsStringList("roles")).contains("internal.clinical");
  }

  @Test
  void aServiceTokenIsRefusedByAnotherServiceAndByTheUserAudience() throws Exception {
    String token = serviceToken("svc-notification");
    String issuer = KeycloakTestSupport.base(KEYCLOAK) + "/realms/shp";
    for (String audience : List.of("svc-scheduling", "shp-api", "svc-notification")) {
      var props =
          new ShpAuthProperties(issuer, audience, true, java.time.Duration.ofSeconds(30), "");
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
      decoder.setJwtValidator(ShpJwt.validator(props, Clock.systemUTC()));
      assertThatThrownBy(() -> decoder.decode(token))
          .as("audience " + audience)
          .isInstanceOf(org.springframework.security.oauth2.jwt.JwtValidationException.class);
    }
  }

  @Test
  void aServiceCannotUseARoleOfAnotherServicesTarget() throws Exception {
    List<String> roles =
        KeycloakTestSupport.claims(serviceToken("svc-notification")).read("$.roles");
    assertThat(roles).doesNotContain("internal.ml", "internal.document", "internal.scheduling");
  }

  @Test
  void wrongSecretAndPasswordGrantAreRejected() throws Exception {
    var wrong =
        KeycloakTestSupport.formResponse(
            tokenUrl(),
            "grant_type=client_credentials&client_id=svc-ai&client_secret=not-the-secret");
    assertThat(wrong.statusCode()).isEqualTo(401);
    var password =
        KeycloakTestSupport.formResponse(
            tokenUrl(),
            "grant_type=password&client_id=svc-ai&client_secret="
                + enc(secretOf("svc-ai"))
                + "&username=anyone&password=anything");
    assertThat(password.statusCode()).isIn(400, 401);
    assertThat(password.body()).contains("unauthorized_client");
  }
}
