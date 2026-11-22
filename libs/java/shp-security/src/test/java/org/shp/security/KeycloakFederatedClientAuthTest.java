package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * IAM-02 decision: services authenticate to Keycloak with their Kubernetes ServiceAccount token
 * (client authentication "Signed JWT - Federated", identity provider type {@code kubernetes}); no
 * client secret exists. The cluster realm is imported as it is deployed; a local HTTP server plays
 * the cluster's OIDC issuer (discovery document + JWKS) and signs the assertions the way the API
 * server signs projected ServiceAccount tokens.
 */
class KeycloakFederatedClientAuthTest {
  static final String NAMESPACE = "shp-apps";
  static GenericContainer<?> issuerServer;
  static Network network;
  static RSAKey issuerKey;
  static String issuerUrl;
  static GenericContainer<?> keycloak;
  static String realmIssuer;
  static String tokenUrl;

  @BeforeAll
  static void start() throws Exception {
    issuerKey =
        new RSAKeyGenerator(2048)
            .keyID("k8s-test")
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .generate();
    network = Network.newNetwork();
    issuerUrl = "http://k8s-issuer:8000";
    String discovery =
        "{\"issuer\":\"" + issuerUrl + "\",\"jwks_uri\":\"" + issuerUrl + "/openid/v1/jwks\"}";
    String jwks = new JWKSet(issuerKey.toPublicJWK()).toString();
    // The cluster's OIDC issuer: a static web server in the same Docker network as Keycloak,
    // serving the discovery
    // document and the public key the way the Kubernetes API server does.
    issuerServer =
        new GenericContainer<>(DockerImageName.parse("busybox:1.37.0"))
            .withNetwork(network)
            .withNetworkAliases("k8s-issuer")
            .withCommand("httpd", "-f", "-p", "8000", "-h", "/www")
            .withCopyToContainer(
                Transferable.of(discovery), "/www/.well-known/openid-configuration")
            .withCopyToContainer(Transferable.of(jwks), "/www/openid/v1/jwks")
            .waitingFor(Wait.forListeningPort());
    issuerServer.start();
    keycloak =
        KeycloakTestSupport.container(
                "identity/realm/dist/realm-shp-prod.json",
                "SHP_PUBLIC_HOST",
                "idp.example.test",
                "SHP_K8S_OIDC_ISSUER",
                issuerUrl)
            .withNetwork(network);
    keycloak.start();
    realmIssuer = KeycloakTestSupport.base(keycloak) + "/realms/shp";
    tokenUrl = realmIssuer + "/protocol/openid-connect/token";
  }

  @AfterAll
  static void stop() {
    if (keycloak != null) {
      keycloak.stop();
    }
    if (issuerServer != null) {
      issuerServer.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  /**
   * What the API server issues for a pod: iss = cluster issuer, sub =
   * system:serviceaccount:ns:name, aud = requested.
   */
  static String assertion(RSAKey key, String serviceAccount, String audience, Instant expires)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuerUrl)
            .subject("system:serviceaccount:" + NAMESPACE + ":" + serviceAccount)
            .audience(audience)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expires))
            .jwtID(UUID.randomUUID().toString())
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(),
            claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  static String good(String serviceAccount) throws Exception {
    return assertion(issuerKey, serviceAccount, realmIssuer, Instant.now().plusSeconds(300));
  }

  static java.net.http.HttpResponse<String> exchange(String clientId, String assertion)
      throws Exception {
    return KeycloakTestSupport.formResponse(
        tokenUrl,
        "grant_type=client_credentials&scope=call-clinical&client_id="
            + clientId
            + "&client_assertion_type="
            + URLEncoder.encode(
                "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", StandardCharsets.UTF_8)
            + "&client_assertion="
            + assertion);
  }

  @Test
  void serviceAccountTokenOfTheRightServiceGetsAnInternalToken() throws Exception {
    var res = exchange("svc-notification", good("notification"));
    assertThat(res.statusCode()).isEqualTo(200);
    var claims = KeycloakTestSupport.claims(JsonPath.read(res.body(), "$.access_token"));
    assertThat(claims.<String>read("$.azp")).isEqualTo("svc-notification");
    assertThat(claims.<List<String>>read("$.roles")).contains("internal.clinical");
    // audience isolation (ADR-0017): the targets only, no shp-api
    Object aud = claims.read("$.aud");
    List<String> audiences =
        aud instanceof List<?> l
            ? l.stream().map(String::valueOf).toList()
            : List.of(String.valueOf(aud));
    assertThat(audiences).containsExactly("svc-clinical").doesNotContain("shp-api");
  }

  @Test
  void aTokenOfAnotherServiceAccountIsRefused() throws Exception {
    // The scheduling pod's token must not obtain svc-notification's identity, even though both are
    // validly signed.
    // Keycloak finds the client by (issuer, sub), so svc-scheduling is the client that
    // authenticates and the
    // request for another client_id is refused ("client_id parameter does not match authenticated
    // client").
    var other = exchange("svc-notification", good("scheduling"));
    assertThat(other.statusCode()).isIn(400, 401);
    assertThat(other.body()).doesNotContain("access_token");
    assertThat(exchange("svc-scheduling", good("scheduling")).statusCode()).isEqualTo(200);
  }

  @Test
  void assertionsThatAreNotForThisRealmOrThisIssuerAreRefused() throws Exception {
    // audience of another realm/service
    assertThat(
            exchange(
                    "svc-ai",
                    assertion(
                        issuerKey,
                        "ai",
                        "https://other.example.test/realms/x",
                        Instant.now().plusSeconds(300)))
                .statusCode())
        .isIn(400, 401);
    // expired
    assertThat(
            exchange(
                    "svc-ai",
                    assertion(issuerKey, "ai", realmIssuer, Instant.now().minusSeconds(600)))
                .statusCode())
        .isIn(400, 401);
    // signed by a key the cluster issuer does not publish
    RSAKey stranger = new RSAKeyGenerator(2048).keyID(issuerKey.getKeyID()).generate();
    assertThat(
            exchange(
                    "svc-ai",
                    assertion(stranger, "ai", realmIssuer, Instant.now().plusSeconds(300)))
                .statusCode())
        .isIn(400, 401);
  }

  @Test
  void delegatedClientRequiresItsOwnFederatedIdentity() throws Exception {
    String base = KeycloakTestSupport.base(keycloak);
    String admin = KeycloakTestSupport.adminToken(base);
    KeycloakTestSupport.createUser(
        base, admin, "patient.fed", "patient", KeycloakTokenExchangeTest.USER_PASSWORD, null);
    var login =
        new KeycloakBrowserLogin(realmIssuer, "shp-web", "https://idp.example.test/cb")
            .signIn("patient.fed", KeycloakTokenExchangeTest.USER_PASSWORD, () -> "");
    assertThat(login.accessToken()).isNotBlank();
    assertThat(KeycloakTokenExchangeTest.audiences(KeycloakTestSupport.claims(login.accessToken())))
        .containsExactly("shp-api");
    String body =
        "grant_type="
            + encode(KeycloakTokenExchangeTest.GRANT)
            + "&subject_token_type="
            + encode(KeycloakTokenExchangeTest.ACCESS)
            + "&subject_token="
            + login.accessToken()
            + "&audience=svc-scheduling&scope=exchange-scheduling&client_id=shp-api"
            + "&client_assertion_type="
            + encode(ClientCredentialsTokenProvider.ASSERTION_TYPE)
            + "&client_assertion=";
    var denied = KeycloakTestSupport.formResponse(tokenUrl, body + good("ai"));
    KeycloakTokenExchangeTest.assertRejected(denied);
    var allowed = KeycloakTestSupport.formResponse(tokenUrl, body + good("ai-exchange"));
    assertThat(allowed.statusCode()).isEqualTo(200);
    assertThat(
            KeycloakTokenExchangeTest.audiences(
                KeycloakTestSupport.claims(JsonPath.read(allowed.body(), "$.access_token"))))
        .containsExactly("svc-scheduling");
    assertThat(exchange("svc-ai", good("ai")).statusCode()).isEqualTo(200);
    KeycloakTokenExchangeTest.assertRejected(exchange("svc-ai", good("ai-exchange")));
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  @Test
  void noClientSecretExistsSoASecretCannotBeUsed() throws Exception {
    var res =
        KeycloakTestSupport.formResponse(
            tokenUrl, "grant_type=client_credentials&client_id=svc-ai&client_secret=guess");
    assertThat(res.statusCode()).isEqualTo(401);
  }
}
