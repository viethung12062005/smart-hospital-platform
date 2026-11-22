package org.shp.clinical;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real HTTP, real authorization interceptors and a real MongoDB 8 replica set, shared by every API
 * test of the service. Only the identity provider is replaced: tokens are signed with a local key.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + ClinicalApiTestBase.ISSUER,
      "shp.auth.audience=" + ClinicalApiTestBase.AUDIENCE
    })
@Import(ClinicalApiTestBase.TestAuth.class)
public abstract class ClinicalApiTestBase {
  public static final String ISSUER = "https://idp.test/realms/shp";
  public static final String AUDIENCE = "shp-api";
  private static final byte[] KEY =
      "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);

  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1") // see MongoTestBase in shp-data-mongo
          .withReplicaSet();

  static {
    DB.start(); // one container for the whole test JVM
  }

  @DynamicPropertySource
  static void mongo(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", () -> DB.getReplicaSetUrl("clinical"));
  }

  @TestConfiguration
  static class TestAuth {
    @Bean
    JwtDecoder testJwtDecoder(ShpAuthProperties props, Clock clock) {
      var decoder =
          NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
              .macAlgorithm(MacAlgorithm.HS256)
              .build();
      decoder.setJwtValidator(ShpJwt.validator(props, clock));
      return decoder;
    }
  }

  @Autowired protected Environment env;
  @Autowired protected MongoTemplate mongo;

  @BeforeEach
  void cleanClinicalData() {
    for (String c :
        List.of(
            "patients",
            "mrn_counters",
            "resource_history",
            "identity_links",
            "profile_links",
            "care_relationships",
            "break_glass_sessions",
            "outbox")) {
      mongo.remove(new Query(), c);
    }
  }

  /** A token for {@code roles}; staff roles carry {@code amr=[mfa]} as the platform requires. */
  protected static String token(String subject, String... roles) {
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(ISSUER)
              .subject(subject)
              .audience(AUDIENCE)
              .claim("roles", List.of(roles))
              .issueTime(Date.from(now.minusSeconds(60)))
              .notBeforeTime(Date.from(now.minusSeconds(60)))
              .expirationTime(Date.from(now.plus(Duration.ofMinutes(10))));
      if (!List.of(roles).contains("patient")) {
        claims.claim("amr", List.of("pwd", "mfa"));
      }
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
      jwt.sign(new MACSigner(KEY));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Link a subject to a Patient or Practitioner so the identity layer accepts it. */
  protected void link(String subject, String fhirUser) {
    mongo.save(
        new Document("_id", subject).append("fhirUser", fhirUser).append("disabled", false),
        "identity_links");
  }

  protected HttpResponse<String> call(
      String method, String path, String token, String body, String... headers) throws Exception {
    var builder =
        HttpRequest.newBuilder(
            URI.create("http://localhost:" + env.getProperty("local.server.port") + path));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    List<String> all = new ArrayList<>(List.of(headers));
    if (body != null && !all.contains("Content-Type")) {
      all.add("Content-Type");
      all.add("application/json");
    }
    for (int i = 0; i < all.size(); i += 2) {
      builder.header(all.get(i), all.get(i + 1));
    }
    builder.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    try (var client = HttpClient.newHttpClient()) {
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  /** Audit records the interceptors queued in the outbox, newest last. */
  protected List<Document> auditRecords() {
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
