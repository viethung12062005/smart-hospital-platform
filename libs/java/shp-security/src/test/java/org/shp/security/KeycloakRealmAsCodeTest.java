package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IAM-01: the realm that Argo CD deploys (identity/realm/dist/realm-shp-prod.json, built from the
 * same source as the local realm) imports into the pinned Keycloak and has what ADR-0017 asks for:
 * PKCE client, {@code shp-api} audience, the five roles, no users, no development client, redirect
 * URI taken from the environment.
 */
@Testcontainers
class KeycloakRealmAsCodeTest {
  static final String HOST = "idp.example.test";

  @Container
  static final GenericContainer<?> KEYCLOAK =
      KeycloakTestSupport.container(
          "identity/realm/dist/realm-shp-prod.json",
          "SHP_PUBLIC_HOST",
          HOST,
          "SHP_K8S_OIDC_ISSUER",
          "https://kubernetes.example.test");

  static String base;
  static String admin;

  @BeforeAll
  static void login() throws Exception {
    base = KeycloakTestSupport.base(KEYCLOAK);
    admin = KeycloakTestSupport.adminToken(base);
  }

  static String get(String path) throws Exception {
    return KeycloakTestSupport.adminGet(base + "/admin/realms/shp" + path, admin);
  }

  @Test
  void hasNoPersonUsersAndNoDevelopmentClient() throws Exception {
    // The user search never lists service-account users, so a non-empty result would be a person.
    assertThat(JsonPath.<List<?>>read(get("/users"), "$")).isEmpty();
    String ai = JsonPath.read(get("/clients?clientId=svc-ai"), "$[0].id");
    assertThat(JsonPath.<String>read(get("/clients/" + ai + "/service-account-user"), "$.username"))
        .isEqualTo("service-account-svc-ai");
    List<String> clientIds = JsonPath.read(get("/clients"), "$[*].clientId");
    assertThat(clientIds).contains("shp-web", "shp-api").doesNotContain("shp-test");
  }

  @Test
  void hasTheFiveRoles() throws Exception {
    List<String> roles = JsonPath.read(get("/roles"), "$[*].name");
    assertThat(roles).contains("patient", "doctor", "nurse", "admin", "dpo");
  }

  @Test
  void webClientIsPublicPkceWithRedirectFromTheEnvironment() throws Exception {
    String clients = get("/clients?clientId=shp-web");
    assertThat(JsonPath.<Boolean>read(clients, "$[0].publicClient")).isTrue();
    assertThat(JsonPath.<Boolean>read(clients, "$[0].directAccessGrantsEnabled")).isFalse();
    assertThat(JsonPath.<String>read(clients, "$[0].attributes['pkce.code.challenge.method']"))
        .isEqualTo("S256");
    assertThat(JsonPath.<List<String>>read(clients, "$[0].redirectUris"))
        .containsExactly("https://" + HOST + "/*");
    List<String> mappers = JsonPath.read(clients, "$[0].protocolMappers[*].name");
    assertThat(mappers).contains("roles-claim", "audience-shp-api");
  }

  @Test
  void authorizationEndpointEnforcesPkceAndTheRedirectUri() throws Exception {
    String auth =
        base
            + "/realms/shp/protocol/openid-connect/auth?client_id=shp-web&response_type=code"
            + "&scope=openid&state=s&nonce=n&redirect_uri=";
    String good = "https%3A%2F%2F" + HOST + "%2Fcallback";
    // Without a code challenge the request is refused by redirecting back with an error.
    var noPkce = KeycloakTestSupport.getNoRedirect(auth + good);
    assertThat(noPkce.statusCode()).isEqualTo(302);
    assertThat(noPkce.headers().firstValue("Location").orElseThrow())
        .contains("error=invalid_request");
    // With S256 the login page is served.
    var pkce =
        KeycloakTestSupport.getNoRedirect(
            auth
                + good
                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256");
    assertThat(pkce.statusCode()).isEqualTo(200);
    // A redirect URI outside the allowed host is rejected without redirecting.
    var evil =
        KeycloakTestSupport.getNoRedirect(
            auth
                + "https%3A%2F%2Fevil.example.test%2Fcb&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256");
    assertThat(evil.statusCode()).isEqualTo(400);
  }

  @Test
  void accessTokenLifespanIsAtMostFifteenMinutes() throws Exception {
    assertThat(JsonPath.<Integer>read(get(""), "$.accessTokenLifespan")).isLessThanOrEqualTo(900);
  }
}
