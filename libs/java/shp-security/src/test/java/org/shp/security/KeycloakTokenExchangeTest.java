package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IAM-07: the {@code ai} service exchanges a user's access token (RFC 8693, Keycloak "standard
 * token exchange" v2, the supported implementation of Keycloak 26.8) for a token that keeps the
 * user's {@code sub} and carries the target's own audience {@code svc-<target>} and nothing else
 * (audience isolation, ADR-0017); no other client can exchange. Runs against the local realm built
 * from the same source as the cluster realm (identity/realm).
 */
@Testcontainers
class KeycloakTokenExchangeTest {
  static final String GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
  static final String ACCESS = "urn:ietf:params:oauth:token-type:access_token";
  static final String USER_PASSWORD = "Test-only-password-1";

  @Container
  static final GenericContainer<?> KEYCLOAK =
      KeycloakTestSupport.container("dev/chart/shp-local-deps/files/realm-shp.json");

  static String base;
  static String tokenUrl;
  static String issuer;
  static String userToken;
  static String userSub;

  static String enc(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
  }

  @BeforeAll
  static void createUserAndSignIn() throws Exception {
    base = KeycloakTestSupport.base(KEYCLOAK);
    issuer = base + "/realms/shp";
    tokenUrl = issuer + "/protocol/openid-connect/token";
    String admin = KeycloakTestSupport.adminToken(base);
    String user =
        """
        {"username":"patient.ex","enabled":true,"emailVerified":true,"email":"patient.ex@example.test",
         "firstName":"Test","lastName":"User",
         "credentials":[{"type":"password","value":"%s","temporary":false}]}
        """
            .formatted(USER_PASSWORD);
    var created = KeycloakTestSupport.admin("POST", base + "/admin/realms/shp/users", admin, user);
    assertThat(created.statusCode()).isEqualTo(201);
    String id = created.headers().firstValue("Location").orElseThrow().replaceAll(".*/", "");
    String role = KeycloakTestSupport.adminGet(base + "/admin/realms/shp/roles/patient", admin);
    var mapped =
        KeycloakTestSupport.admin(
            "POST",
            base + "/admin/realms/shp/users/" + id + "/role-mappings/realm",
            admin,
            "[" + role + "]");
    assertThat(mapped.statusCode()).isEqualTo(204);
    userToken =
        JsonPath.read(
            KeycloakTestSupport.form(
                tokenUrl,
                "grant_type=password&client_id=shp-test&username=patient.ex&password="
                    + enc(USER_PASSWORD)),
            "$.access_token");
    userSub = KeycloakTestSupport.claims(userToken).read("$.sub");
  }

  static String secretOf(String clientId) throws Exception {
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

  static HttpResponse<String> exchange(String clientId, String subjectToken, String... audiences)
      throws Exception {
    StringBuilder body =
        new StringBuilder("grant_type=" + enc(GRANT))
            .append("&client_id=")
            .append(clientId)
            .append("&client_secret=")
            .append(enc(secretOf(clientId)))
            .append("&subject_token=")
            .append(subjectToken)
            .append("&subject_token_type=")
            .append(enc(ACCESS))
            .append("&requested_token_type=")
            .append(enc(ACCESS));
    for (String a : audiences) {
      body.append("&audience=").append(a);
    }
    return KeycloakTestSupport.formResponse(tokenUrl, body.toString());
  }

  static List<String> audiences(DocumentContext claims) {
    Object aud = claims.read("$.aud");
    return aud instanceof List<?> l
        ? l.stream().map(String::valueOf).toList()
        : List.of(String.valueOf(aud));
  }

  @Test
  void userTokenNamesTheAiServiceSoItCanBeExchanged() {
    assertThat(audiences(KeycloakTestSupport.claims(userToken))).contains("shp-api", "svc-ai");
  }

  @Test
  void exchangedTokenKeepsTheUserSubAndNamesTheTargetService() throws Exception {
    var res = exchange("svc-ai", userToken, "svc-clinical");
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(JsonPath.<String>read(res.body(), "$.issued_token_type")).isEqualTo(ACCESS);
    DocumentContext claims =
        KeycloakTestSupport.claims(JsonPath.read(res.body(), "$.access_token"));
    assertThat(claims.<String>read("$.sub"))
        .isEqualTo(userSub); // the user, not the service account
    assertThat(claims.<String>read("$.azp")).isEqualTo("svc-ai");
    // audience isolation: the target's own audience only, no shp-api, no other service
    assertThat(audiences(claims)).containsExactly("svc-clinical");
    // The user's own roles apply unchanged, so the authorization matrix decides (15 section 7); no
    // service role leaks in.
    assertThat(claims.<List<String>>read("$.roles")).contains("patient");
    assertThat(claims.<List<String>>read("$.roles")).noneMatch(r -> r.startsWith("internal."));
  }

  @Test
  void exchangedTokenPassesTheTargetServicesJwtRules() throws Exception {
    String token =
        JsonPath.read(exchange("svc-ai", userToken, "svc-scheduling").body(), "$.access_token");
    // scheduling accepts its own svc-scheduling (and shp-api for user tokens)
    var scheduling =
        new ShpAuthProperties(issuer, "svc-scheduling", true, Duration.ofSeconds(30), "");
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
    decoder.setJwtValidator(ShpJwt.validator(scheduling, Clock.systemUTC()));
    Jwt jwt = decoder.decode(token);
    assertThat(jwt.getSubject()).isEqualTo(userSub);
    assertThat(ShpJwt.roles(jwt)).containsExactly("patient");
    // audience isolation: an exchanged token minted for scheduling is refused by clinical
    var clinical = new ShpAuthProperties(issuer, "svc-clinical", true, Duration.ofSeconds(30), "");
    NimbusJwtDecoder c =
        NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
    c.setJwtValidator(ShpJwt.validator(clinical, Clock.systemUTC()));
    assertThatThrownBy(() -> c.decode(token)).isInstanceOf(JwtValidationException.class);
  }

  @Test
  void onlyTheTargetsOfTheCallerCanBeRequested() throws Exception {
    // ml is a service but not an exchange target of ai: the token cannot be widened by asking for
    // it.
    var res = exchange("svc-ai", userToken, "svc-ml");
    assertThat(res.statusCode()).isIn(400, 403);
    assertThat(res.body()).doesNotContain("access_token");
  }

  @Test
  void otherClientsCannotExchange() throws Exception {
    for (String client :
        List.of("svc-scheduling", "svc-clinical", "svc-notification", "svc-document", "svc-ml")) {
      var res = exchange(client, userToken, "svc-clinical");
      assertThat(res.statusCode()).as(client + " " + res.body()).isIn(400, 401, 403);
      assertThat(res.body()).as(client).doesNotContain("access_token");
    }
    // A public client (the web app) cannot exchange either.
    var pub =
        KeycloakTestSupport.formResponse(
            tokenUrl,
            "grant_type="
                + enc(GRANT)
                + "&client_id=shp-test&subject_token="
                + userToken
                + "&subject_token_type="
                + enc(ACCESS)
                + "&audience=svc-clinical");
    assertThat(pub.statusCode()).isIn(400, 401, 403);
    assertThat(pub.body()).doesNotContain("access_token");
  }

  @Test
  void aServiceAccountTokenCannotBeExchangedIntoAUser() throws Exception {
    // svc-ai's own token has no user: exchanging it must not produce a user token for anyone.
    String serviceToken =
        JsonPath.read(
            KeycloakTestSupport.form(
                tokenUrl,
                "grant_type=client_credentials&client_id=svc-ai&client_secret="
                    + enc(secretOf("svc-ai"))),
            "$.access_token");
    var res = exchange("svc-ai", serviceToken, "svc-clinical");
    if (res.statusCode() == 200) {
      assertThat(
              KeycloakTestSupport.claims(JsonPath.read(res.body(), "$.access_token"))
                  .<String>read("$.sub"))
          .isNotEqualTo(userSub);
    }
  }

  @Test
  void aTamperedOrForeignSubjectTokenIsRefused() throws Exception {
    String sig = userToken.substring(userToken.lastIndexOf('.') + 1);
    String flipped = (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1);
    String tampered = userToken.substring(0, userToken.lastIndexOf('.') + 1) + flipped;
    var res = exchange("svc-ai", tampered, "svc-clinical");
    assertThat(res.statusCode()).isIn(400, 401, 403);
    assertThat(res.body()).doesNotContain("access_token");
  }

  /** Switch standard token exchange of a client on or off through the admin API. */
  static void setExchange(String clientId, boolean enabled) throws Exception {
    String admin = KeycloakTestSupport.adminToken(base);
    String list =
        KeycloakTestSupport.adminGet(
            base + "/admin/realms/shp/clients?clientId=" + clientId, admin);
    String id = JsonPath.read(list, "$[0].id");
    var rep =
        JsonPath.parse(
            KeycloakTestSupport.adminGet(base + "/admin/realms/shp/clients/" + id, admin));
    rep.put("$.attributes", "standard.token.exchange.enabled", String.valueOf(enabled));
    var res =
        KeycloakTestSupport.admin(
            "PUT", base + "/admin/realms/shp/clients/" + id, admin, rep.jsonString());
    assertThat(res.statusCode()).isEqualTo(204);
  }

  @Test
  void theClientSwitchIsWhatAllowsTheExchange() throws Exception {
    setExchange("svc-ai", false);
    try {
      var res = exchange("svc-ai", userToken, "svc-clinical");
      assertThat(res.statusCode()).isIn(400, 401, 403);
      assertThat(res.body()).doesNotContain("access_token");
    } finally {
      setExchange("svc-ai", true);
    }
    assertThat(exchange("svc-ai", userToken, "svc-clinical").statusCode()).isEqualTo(200);
  }
}
