package org.shp.document.api;

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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.document.domain.BlobStore;
import org.shp.document.domain.CareLookup;
import org.shp.document.domain.SignedUrl;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.shp.web.auth.IdentityLink;
import org.shp.web.auth.IdentityLinkClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** D1 through the real controller, authorization matrix and MongoDB (synthetic identities). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Import(DocumentUploadApiTest.Fakes.class)
@Testcontainers
class DocumentUploadApiTest {
  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();

  static final byte[] KEY =
      "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);
  static final String PATIENT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
  static final String DOCTOR = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
  static final String NURSE = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e11";
  static final String HASH = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, IdentityLink> LINKS = new ConcurrentHashMap<>();
  static final Set<String> CARE = ConcurrentHashMap.newKeySet();

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    IdentityLinkClient testLinks() {
      return subject -> Optional.ofNullable(LINKS.get(subject));
    }

    @Bean
    @Primary
    CareLookup testCare() {
      return (practitioner, patient) -> CARE.contains(practitioner + "|" + patient);
    }

    @Bean
    @Primary
    BlobStore testBlobs() {
      return new BlobStore() {
        public void put(String c, String p, byte[] d, String t) {}

        public byte[] get(String c, String p) {
          throw new BlobNotFoundException(c, p);
        }

        public Optional<BlobInfo> stat(String c, String p) {
          return Optional.empty();
        }

        public boolean delete(String c, String p) {
          return false;
        }

        public SignedUrl uploadUrl(String c, String p) {
          return new SignedUrl(
              "https://blob.test/" + c + "/" + p + "?sig=test", Instant.now().plusSeconds(600));
        }

        public SignedUrl downloadUrl(String c, String p, String f) {
          throw new UnsupportedOperationException();
        }
      };
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

  @Autowired Environment env;
  @Autowired MongoTemplate mongo;

  @BeforeEach
  void reset() {
    LINKS.clear();
    CARE.clear();
    LINKS.put("doc", new IdentityLink("Practitioner", DOCTOR, false));
    LINKS.put("nurse-1", new IdentityLink("Practitioner", NURSE, false));
    LINKS.put("pat-a", new IdentityLink("Patient", PATIENT, false));
    mongo.dropCollection("document_references");
    mongo.dropCollection("upload_sessions");
    mongo.dropCollection("idempotency_keys");
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

  HttpResponse<String> post(String bearer, String idempotencyKey, String body) throws Exception {
    var b =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + env.getProperty("local.server.port")
                        + "/api/v1/documents/uploads"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (idempotencyKey != null) {
      b.header("Idempotency-Key", idempotencyKey);
    }
    if (bearer != null) {
      b.header("Authorization", "Bearer " + bearer);
    }
    try (var c = HttpClient.newHttpClient()) {
      return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  static String body(String contentType, String size) {
    return UploadRequestTest.json(contentType, size, "11502-2", "synthetic.pdf");
  }

  static JsonNode json(HttpResponse<String> r) {
    return JSON.readTree(r.body());
  }

  @Test
  void aDoctorWithCareGets201AndTheDocumentIsPending() throws Exception {
    CARE.add(DOCTOR + "|" + PATIENT);
    var r = post(token("doctor", "doc"), "key-0000001", body("application/pdf", "482113"));
    assertThat(r.statusCode()).isEqualTo(201);
    var j = json(r);
    assertThat(j.get("upload").get("method").asString()).isEqualTo("PUT");
    var doc =
        mongo.findById(
            j.get("documentId").asString(), org.bson.Document.class, "document_references");
    assertThat(doc.get("processing", org.bson.Document.class).getString("state"))
        .isEqualTo("pending-upload");
    assertThat(doc.get("resource", org.bson.Document.class).getString("docStatus"))
        .isEqualTo("preliminary");
  }

  @Test
  void aNurseNeedsNoCare() throws Exception {
    var r = post(token("nurse", "nurse-1"), "key-0000002", body("image/png", "1000"));
    assertThat(r.statusCode()).isEqualTo(201);
  }

  @Test
  void theSameKeyReturnsTheSameDocumentId() throws Exception {
    var first = post(token("nurse", "nurse-1"), "key-0000003", body("application/pdf", "10"));
    var again = post(token("nurse", "nurse-1"), "key-0000003", body("application/pdf", "10"));
    assertThat(json(again).get("documentId").asString())
        .isEqualTo(json(first).get("documentId").asString());
    assertThat(json(again).get("uploadId").asString())
        .isEqualTo(json(first).get("uploadId").asString());
    assertThat(mongo.getCollection("document_references").countDocuments()).isEqualTo(1);
  }

  @Test
  void theSameKeyWithAnotherBodyIs422() throws Exception {
    post(token("nurse", "nurse-1"), "key-0000004", body("application/pdf", "10"));
    var r = post(token("nurse", "nurse-1"), "key-0000004", body("application/pdf", "11"));
    assertThat(r.statusCode()).isEqualTo(422);
  }

  @Test
  void aMissingKeyIs428() throws Exception {
    assertThat(post(token("nurse", "nurse-1"), null, body("application/pdf", "10")).statusCode())
        .isEqualTo(428);
  }

  @Test
  void anotherFileTypeIs415AndATooLargeFileIs413() throws Exception {
    assertThat(
            post(token("nurse", "nurse-1"), "key-0000005", body("application/zip", "10"))
                .statusCode())
        .isEqualTo(415);
    assertThat(
            post(token("nurse", "nurse-1"), "key-0000006", body("application/pdf", "20971521"))
                .statusCode())
        .isEqualTo(413);
    assertThat(mongo.getCollection("document_references").countDocuments()).isZero();
  }

  @Test
  void aBadBodyIs400() throws Exception {
    assertThat(post(token("nurse", "nurse-1"), "key-0000007", "{}").statusCode()).isEqualTo(400);
  }

  @Test
  void aDoctorWithoutCareIs403AndNothingIsCreated() throws Exception {
    var r = post(token("doctor", "doc"), "key-0000008", body("application/pdf", "10"));
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(json(r).get("code").asString()).isEqualTo("no-care-relationship");
    assertThat(mongo.getCollection("document_references").countDocuments()).isZero();
  }

  @Test
  void aPatientAndAnAnonymousCallerAreRefused() throws Exception {
    assertThat(
            post(token("patient", "pat-a"), "key-0000009", body("application/pdf", "10"))
                .statusCode())
        .isEqualTo(403);
    assertThat(post(null, "key-0000010", body("application/pdf", "10")).statusCode())
        .isEqualTo(401);
  }
}
