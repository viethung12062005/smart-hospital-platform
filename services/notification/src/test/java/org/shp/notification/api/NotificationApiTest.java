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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** N1-N4 through the real controller, authorization matrix and MongoDB (synthetic identities). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Import(NotificationApiTest.Auth.class)
@Testcontainers
class NotificationApiTest {
  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();

  static final byte[] KEY =
      "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);
  static final String PATIENT_A = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
  static final String PATIENT_B = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e04";
  static final String DOCTOR = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
  static final ObjectMapper JSON = new ObjectMapper();

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

  @Autowired Environment env;
  @Autowired MongoTemplate mongo;

  @BeforeEach
  void reset() {
    LINKS.clear();
    LINKS.put("pat-a", new IdentityLink("Patient", PATIENT_A, false));
    LINKS.put("pat-b", new IdentityLink("Patient", PATIENT_B, false));
    LINKS.put("doc", new IdentityLink("Practitioner", DOCTOR, false));
    mongo.dropCollection("device_tokens");
    mongo.dropCollection("preferences");
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

  HttpResponse<String> call(String method, String path, String bearer, String body)
      throws Exception {
    var b =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    if ("PUT".equals(method)) {
      // 07 §3: the platform requires a well-formed If-Match on every PUT (value not compared here).
      b.header("If-Match", "W/\"1\"");
    }
    if (bearer != null) {
      b.header("Authorization", "Bearer " + bearer);
    }
    try (var c = HttpClient.newHttpClient()) {
      return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  static JsonNode json(HttpResponse<String> r) {
    return JSON.readTree(r.body());
  }

  static String device(String platform, String tokenValue) {
    return "{\"platform\":\"" + platform + "\",\"token\":\"" + tokenValue + "\"}";
  }

  // ---------- N1 ----------
  @Test
  void registerTakesTheOwnerFromIdentityAndIgnoresTheBody() throws Exception {
    var body = "{\"platform\":\"ios\",\"token\":\"tok-1\",\"ownerRef\":\"Patient/someone-else\"}";
    var r = call("PUT", "/api/v1/notifications/devices", token("patient", "pat-a"), body);
    assertThat(r.statusCode()).isEqualTo(200);
    var stored = mongo.findById(json(r).get("tokenId").asString(), Document.class, "device_tokens");
    assertThat(stored.getString("ownerRef")).isEqualTo("Patient/" + PATIENT_A);
    assertThat(stored.getString("platform")).isEqualTo("ios");
  }

  @Test
  void registerIsAnUpsertByToken() throws Exception {
    var first =
        call(
            "PUT",
            "/api/v1/notifications/devices",
            token("patient", "pat-a"),
            device("ios", "tok-1"));
    var again =
        call(
            "PUT",
            "/api/v1/notifications/devices",
            token("patient", "pat-a"),
            device("web", "tok-1"));
    assertThat(json(again).get("tokenId").asString())
        .isEqualTo(json(first).get("tokenId").asString());
    assertThat(mongo.getCollection("device_tokens").countDocuments()).isEqualTo(1);
    var stored =
        mongo.findById(json(first).get("tokenId").asString(), Document.class, "device_tokens");
    assertThat(stored.getString("platform")).isEqualTo("web");
  }

  @Test
  void aTokenRegisteredByAnotherUserMovesToTheLatestOwner() throws Exception {
    call("PUT", "/api/v1/notifications/devices", token("patient", "pat-a"), device("ios", "tok-1"));
    var r =
        call(
            "PUT", "/api/v1/notifications/devices", token("doctor", "doc"), device("ios", "tok-1"));
    var stored = mongo.findById(json(r).get("tokenId").asString(), Document.class, "device_tokens");
    assertThat(stored.getString("ownerRef")).isEqualTo("Practitioner/" + DOCTOR);
    assertThat(mongo.getCollection("device_tokens").countDocuments()).isEqualTo(1);
  }

  @Test
  void registerRejectsBadInputWith400() throws Exception {
    var t = token("patient", "pat-a");
    for (String body :
        List.of(
            device("ios", ""),
            device("ios", "x".repeat(4097)),
            device("blackberry", "tok-1"),
            "{\"platform\":\"ios\"}",
            "{not json")) {
      var r = call("PUT", "/api/v1/notifications/devices", t, body);
      assertThat(r.statusCode()).as(body).isEqualTo(400);
    }
    var tooLongButAllowed = device("android", "y".repeat(4096));
    assertThat(call("PUT", "/api/v1/notifications/devices", t, tooLongButAllowed).statusCode())
        .isEqualTo(200);
  }

  @Test
  void registerNeedsAnAuthenticatedKnownCaller() throws Exception {
    assertThat(call("PUT", "/api/v1/notifications/devices", null, device("ios", "t")).statusCode())
        .isEqualTo(401);
    assertThat(
            call(
                    "PUT",
                    "/api/v1/notifications/devices",
                    token("patient", "nobody"),
                    device("ios", "t"))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            call(
                    "PUT",
                    "/api/v1/notifications/devices",
                    token("admin", "pat-a"),
                    device("ios", "t"))
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void putWithoutIfMatchIs428BeforeAnythingIsWritten() throws Exception {
    var b =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + env.getProperty("local.server.port")
                        + "/api/v1/notifications/devices"))
            .header("Authorization", "Bearer " + token("patient", "pat-a"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(device("ios", "tok-1")));
    try (var c = HttpClient.newHttpClient()) {
      assertThat(c.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(428);
    }
    assertThat(mongo.getCollection("device_tokens").countDocuments()).isZero();
  }

  // ---------- N2 ----------
  @Test
  void ownerUnregistersAndOthersGetNotFound() throws Exception {
    var id =
        json(call(
                "PUT",
                "/api/v1/notifications/devices",
                token("patient", "pat-a"),
                device("ios", "tok-1")))
            .get("tokenId")
            .asString();
    var path = "/api/v1/notifications/devices/" + id;

    assertThat(call("DELETE", path, token("patient", "pat-b"), null).statusCode()).isEqualTo(404);
    assertThat(call("DELETE", path, token("doctor", "doc"), null).statusCode()).isEqualTo(404);
    assertThat(mongo.getCollection("device_tokens").countDocuments()).isEqualTo(1);

    assertThat(call("DELETE", path, token("patient", "pat-a"), null).statusCode()).isEqualTo(200);
    assertThat(mongo.getCollection("device_tokens").countDocuments()).isZero();
    assertThat(call("DELETE", path, token("patient", "pat-a"), null).statusCode()).isEqualTo(404);
  }

  @Test
  void aDoctorUnregistersOwnDevice() throws Exception {
    var id =
        json(call(
                "PUT",
                "/api/v1/notifications/devices",
                token("doctor", "doc"),
                device("web", "tok-d")))
            .get("tokenId")
            .asString();
    assertThat(
            call("DELETE", "/api/v1/notifications/devices/" + id, token("doctor", "doc"), null)
                .statusCode())
        .isEqualTo(200);
  }

  // ---------- N3 / N4 ----------
  @Test
  void preferencesRoundTripInTheGivenChannelOrder() throws Exception {
    var t = token("patient", "pat-a");
    assertThat(call("GET", "/api/v1/notifications/preferences", t, null).statusCode())
        .isEqualTo(404);

    var put =
        call(
            "PUT",
            "/api/v1/notifications/preferences",
            t,
            "{\"channels\":[\"push\",\"email\",\"sms\"],\"locale\":\"vi\","
                + "\"quietHours\":{\"start\":\"22:00\",\"end\":\"07:00\"}}");
    assertThat(put.statusCode()).isEqualTo(200);

    var got = json(call("GET", "/api/v1/notifications/preferences", t, null));
    assertThat(got.get("channels").toString()).isEqualTo("[\"push\",\"email\",\"sms\"]");
    assertThat(got.get("locale").asString()).isEqualTo("vi");
    assertThat(got.get("quietHours").get("start").asString()).isEqualTo("22:00");
    assertThat(mongo.findById(PATIENT_A, Document.class, "preferences").getString("quietEnd"))
        .isEqualTo("07:00");
  }

  @Test
  void preferencesBelongToTheCallerAndQuietHoursCanBeCleared() throws Exception {
    var a = token("patient", "pat-a");
    call(
        "PUT",
        "/api/v1/notifications/preferences",
        a,
        "{\"channels\":[\"sms\"],\"locale\":\"en\",\"quietHours\":{\"start\":\"21:00\",\"end\":\"06:30\"}}");
    assertThat(
            call("GET", "/api/v1/notifications/preferences", token("patient", "pat-b"), null)
                .statusCode())
        .isEqualTo(404);

    call(
        "PUT",
        "/api/v1/notifications/preferences",
        a,
        "{\"channels\":[\"email\"],\"locale\":\"en\"}");
    var got = json(call("GET", "/api/v1/notifications/preferences", a, null));
    assertThat(got.get("channels").toString()).isEqualTo("[\"email\"]");
    assertThat(got.has("quietHours") && !got.get("quietHours").isNull()).isFalse();
    assertThat(mongo.findById(PATIENT_A, Document.class, "preferences"))
        .doesNotContainKey("quietStart");
  }

  @Test
  void aChannelOutsideThePermittedSetIs400() throws Exception {
    var t = token("patient", "pat-a");
    for (String body :
        List.of(
            "{\"channels\":[\"fax\"],\"locale\":\"vi\"}",
            "{\"channels\":[],\"locale\":\"vi\"}",
            "{\"channels\":[\"push\",\"push\"],\"locale\":\"vi\"}",
            "{\"locale\":\"vi\"}",
            "{\"channels\":[\"push\"],\"locale\":\"fr\"}",
            "{\"channels\":[\"push\"],\"locale\":\"vi\",\"quietHours\":{\"start\":\"25:00\",\"end\":\"07:00\"}}",
            "{\"channels\":[\"push\"],\"locale\":\"vi\",\"quietHours\":{\"start\":\"22:00\"}}")) {
      var r = call("PUT", "/api/v1/notifications/preferences", t, body);
      assertThat(r.statusCode()).as(body).isEqualTo(400);
      assertThat(json(r).get("code").asString()).isEqualTo("validation-failed");
    }
    assertThat(mongo.getCollection("preferences").countDocuments()).isZero();
  }

  @Test
  void staffCannotUseThePatientPreferences() throws Exception {
    var body = "{\"channels\":[\"push\"],\"locale\":\"vi\"}";
    assertThat(
            call("PUT", "/api/v1/notifications/preferences", token("doctor", "doc"), body)
                .statusCode())
        .isEqualTo(403);
    assertThat(
            call("GET", "/api/v1/notifications/preferences", token("doctor", "doc"), null)
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void decisionsAreAudited() throws Exception {
    long before = mongo.getCollection("outbox").countDocuments();
    call("PUT", "/api/v1/notifications/devices", token("patient", "pat-a"), device("ios", "tok-1"));
    call("GET", "/api/v1/notifications/preferences", token("doctor", "doc"), null);
    assertThat(mongo.getCollection("outbox").countDocuments()).isGreaterThan(before);
  }
}
