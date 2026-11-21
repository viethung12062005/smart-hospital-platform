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
 * shp-api}; ADR-0017), selected explicitly, and can do nothing else.
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

  static java.net.http.HttpResponse<String> request(String clientId, String params)
      throws Exception {
    return KeycloakTestSupport.formResponse(
        tokenUrl(),
        "grant_type=client_credentials&client_id="
            + clientId
            + "&client_secret="
            + enc(secretOf(clientId))
            + params);
  }

  static String serviceToken(String clientId, String target) throws Exception {
    var response = request(clientId, "&scope=call-" + target);
    assertThat(response.statusCode()).as(clientId + " selecting " + target).isEqualTo(200);
    return JsonPath.read(response.body(), "$.access_token");
  }

  @Test
  void everyServiceGetsExactlyOneRequestedAudienceAndOnlyItsInternalRoles() throws Exception {
    String spec =
        java.nio.file.Files.readString(
            KeycloakTestSupport.repoFile("identity/realm/services.json"));
    Map<String, Map<String, List<String>>> services = JsonPath.read(spec, "$.services");
    assertThat(services).isNotEmpty();
    for (var entry : services.entrySet()) {
      List<String> calls = entry.getValue().get("calls");
      String caller = "svc-" + entry.getKey();
      for (String target : calls) {
        var claims = KeycloakTestSupport.claims(serviceToken(caller, target));
        List<String> roles = claims.read("$.roles");
        assertThat(roles.stream().filter(r -> r.startsWith("internal.")))
            .containsExactlyInAnyOrderElementsOf(calls.stream().map(c -> "internal." + c).toList());
        assertThat(claims.<String>read("$.azp")).isEqualTo(caller);
        assertThat(KeycloakTokenExchangeTest.audiences(claims)).containsExactly("svc-" + target);
      }
    }
  }

  @Test
  void missingMultipleUnknownAndExchangeOnlyTargetsAreRejected() throws Exception {
    for (String params :
        List.of(
            "",
            "&audience=svc-clinical",
            "&scope=call-clinical+call-ml",
            "&scope=call-document",
            "&scope=call-clinical+call-document",
            "&scope=call-clinical&scope=call-ml")) {
      assertRejected(request("svc-scheduling", params));
    }
    assertRejected(request("svc-ai", "&scope=call-scheduling"));
    assertRejected(request("svc-ai", "&scope=exchange-scheduling"));
    assertRejected(request("shp-api", "&scope=exchange-scheduling"));
  }

  private static void assertRejected(java.net.http.HttpResponse<String> response) {
    assertThat(response.statusCode()).isIn(400, 401, 403);
    assertThat(response.body().contains("\"access_token\"")).as("includes access token").isFalse();
  }

  @Test
  void serviceTokenPassesTheSharedJwtRulesButCarriesNoUserRole() throws Exception {
    String token = serviceToken("svc-notification", "clinical");
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
    // The caller can request both clinical and ml, and this token carries both internal roles.
    // Its clinical audience must still prevent it from being used at ml.
    String token = serviceToken("svc-scheduling", "clinical");
    String issuer = KeycloakTestSupport.base(KEYCLOAK) + "/realms/shp";
    for (String audience : List.of("svc-ml", "shp-api", "svc-scheduling")) {
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
        KeycloakTestSupport.claims(serviceToken("svc-notification", "clinical")).read("$.roles");
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
