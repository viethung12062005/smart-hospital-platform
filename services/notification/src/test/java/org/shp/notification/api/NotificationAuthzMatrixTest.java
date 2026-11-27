package org.shp.notification.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.authz.AuthzMatrix;
import org.shp.authz.AuthzRule;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.shp.web.auth.IdentityLink;
import org.shp.web.auth.IdentityLinkClient;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.authz.UnprotectedEndpoints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * NTF-14 (NFR-SEC2; 15 §3): the authorization matrix over every endpoint notification serves,
 * driven by {@code contracts/authz/matrix.yaml}. The endpoints are found at runtime, so one added
 * later is picked up without editing this test. Over real HTTP and MongoDB (synthetic identities):
 *
 * <ul>
 *   <li>every endpoint carries {@link RequireShpAuthz} and names a cell of the matrix;
 *   <li>a call without a token is 401;
 *   <li>each role the matrix gives no rule for the endpoint's (resource, action) is refused, and
 *       every refusal is audited;
 *   <li>Admin and DPO get no rule on anything but the notification operations the matrix lists.
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Import(NotificationAuthzMatrixTest.Auth.class)
@Testcontainers
class NotificationAuthzMatrixTest {
  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();

  static final byte[] KEY =
      "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);
  static final AuthzMatrix MATRIX = AuthzMatrix.loadEmbedded();
  static final String PATIENT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
  static final String PRACTITIONER = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e11";
  static final Map<String, IdentityLink> LINKS = new ConcurrentHashMap<>();

  @TestConfiguration
  static class Auth {
    @Bean
    @Primary
    IdentityLinkClient testLinks() {
      return subject -> Optional.ofNullable(LINKS.get(subject));
    }

    @Bean
    JwtDecoder testJwtDecoder(ShpAuthProperties props, Clock clock) {
      var d =
          NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
              .macAlgorithm(MacAlgorithm.HS256)
              .build();
      d.setJwtValidator(ShpJwt.validator(props, clock));
      return d;
    }
  }

  /** One guarded endpoint as Spring maps it. */
  record Guarded(String method, String pattern, RequireShpAuthz meta) {
    @Override
    public String toString() {
      return method + " " + pattern + " (" + meta.resource() + "." + meta.action() + ")";
    }
  }

  static final String DEVICE_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e88";

  @Autowired ApplicationContext ctx;
  @Autowired Environment env;
  @Autowired MongoTemplate mongo;

  @BeforeEach
  void reset() {
    LINKS.clear();
    for (String role : MATRIX.roles()) {
      LINKS.put(
          "sub-" + role,
          "patient".equals(role)
              ? new IdentityLink("Patient", PATIENT, false)
              : new IdentityLink("Practitioner", PRACTITIONER, false));
    }
    mongo.dropCollection("outbox");
    // A device the authorization decision can load: it belongs to somebody else, so only a role
    // with a rule for it reaches the handler.
    mongo.dropCollection("device_tokens");
    mongo.save(
        new org.bson.Document("_id", DEVICE_ID)
            .append("token", "synthetic-device-token")
            .append("platform", "android")
            .append("ownerRef", "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e77"),
        "device_tokens");
  }

  @Test
  void everyEndpointDeclaresItsAuthorizationRule() {
    assertThat(UnprotectedEndpoints.find(ctx)).isEmpty();
  }

  @Test
  void everyGuardedEndpointNamesACellOfTheMatrix() {
    assertThat(guarded()).isNotEmpty();
    assertThat(guarded())
        .allSatisfy(g ->
            assertThat(MATRIX.actionsByResource().getOrDefault(g.meta().resource(), List.of()))
                .as(g.toString())
                .contains(g.meta().action()));
  }

  @Test
  void withoutATokenEveryEndpointIs401() throws Exception {
    List<String> wrong = new ArrayList<>();
    for (Guarded g : guarded()) {
      int status = callAs(null, g);
      if (status != 401) {
        wrong.add(g + " -> " + status);
      }
    }
    assertThat(wrong).isEmpty();
  }

  /** Roles without a rule for the endpoint's (resource, action): refused, and audited. */
  @Test
  void rolesWithoutARuleAreRefusedOnEveryEndpointWithAnAudit() throws Exception {
    List<String> wrong = new ArrayList<>();
    int calls = 0;
    for (Guarded g : guarded()) {
      Set<String> allowedRoles =
          MATRIX.rulesFor(g.meta().resource(), g.meta().action()).stream()
              .map(AuthzRule::role)
              .collect(Collectors.toSet());
      for (String role : MATRIX.roles()) {
        if (allowedRoles.contains(role)) {
          continue;
        }
        int status = callAs(role, g);
        calls++;
        int expected = g.meta().denyAsNotFound() ? 404 : 403;
        if (status != expected) {
          wrong.add(g + " as " + role + " -> " + status + ", expected " + expected);
        }
      }
    }
    assertThat(wrong).isEmpty();
    assertThat(calls).isPositive();
    assertThat(auditRecords())
        .filteredOn(a -> "denied".equals(a.getString("outcome")))
        .hasSize(calls);
  }

  /** DPO has no notification operation at all; Admin only the dead-letter ones and N5. */
  @Test
  void dpoIsRefusedEverywhereAndAdminOnlyReachesTheOperationsTheMatrixGivesIt() throws Exception {
    for (Guarded g : guarded()) {
      int refused = g.meta().denyAsNotFound() ? 404 : 403;
      assertThat(callAs("dpo", g)).as(g.toString()).isEqualTo(refused);
      boolean adminRule =
          MATRIX.rulesFor(g.meta().resource(), g.meta().action()).stream()
              .anyMatch(r -> "admin".equals(r.role()));
      if (!adminRule) {
        assertThat(callAs("admin", g)).as(g.toString()).isEqualTo(refused);
      }
    }
  }

  List<Guarded> guarded() {
    List<Guarded> out = new ArrayList<>();
    ctx.getBeansOfType(RequestMappingHandlerMapping.class)
        .values()
        .forEach(m ->
            m.getHandlerMethods()
                .forEach((info, hm) -> {
                  RequireShpAuthz meta = hm.getMethodAnnotation(RequireShpAuthz.class);
                  if (meta == null
                      || !hm.getBeanType().getName().startsWith("org.shp.notification.")) {
                    return;
                  }
                  Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                  for (String pattern : info.getPatternValues()) {
                    for (RequestMethod method :
                        methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                      out.add(new Guarded(method.name(), pattern, meta));
                    }
                  }
                }));
    return out;
  }

  /** Call {@code g} as the linked account of {@code role} (no token when role is null). */
  int callAs(String role, Guarded g) throws Exception {
    String path =
        g.pattern()
            .replaceAll("\\{tokenId}", DEVICE_ID)
            .replaceAll("\\{[^}]+}", "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e99");
    if (path.endsWith("/reminders")) {
      path += "?appointment=0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e21";
    }
    boolean hasBody = Set.of("POST", "PUT", "PATCH").contains(g.method());
    var b =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
            .method(
                g.method(),
                hasBody
                    ? HttpRequest.BodyPublishers.ofString("{}")
                    : HttpRequest.BodyPublishers.noBody());
    if (hasBody) {
      b.header("Content-Type", "application/json");
    }
    if ("PUT".equals(g.method())) {
      b.header("If-Match", "W/\"1\"");
    }
    if (role != null) {
      b.header("Authorization", "Bearer " + token(role, "sub-" + role));
    }
    try (var c = HttpClient.newHttpClient()) {
      return c.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
  }

  static String token(String role, String subject) {
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("https://idp.test/realms/shp")
              .subject(subject)
              .audience("shp-api")
              .claim("roles", List.of(role))
              .claim("amr", List.of("mfa"))
              .issueTime(Date.from(now.minusSeconds(60)))
              .notBeforeTime(Date.from(now.minusSeconds(60)))
              .expirationTime(Date.from(now.plusSeconds(600)));
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
      jwt.sign(new MACSigner(KEY));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  List<Document> auditRecords() {
    return mongo
        .find(
            Query.query(Criteria.where("eventType").is("shp.audit.recorded.v1")),
            Document.class,
            "outbox")
        .stream()
        .map(d -> d.get("payload", Document.class).get("data", Document.class))
        .toList();
  }
}
