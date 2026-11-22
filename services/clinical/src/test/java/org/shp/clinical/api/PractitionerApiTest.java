package org.shp.clinical.api;

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
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.infra.PractitionerRepository;
import org.shp.data.mongo.MongoMessaging;
import org.shp.fhir.FhirJson;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * C9/C10 over HTTP, with a real MongoDB: the directory pages with a signed {@code _page_token} and
 * a {@code Bundle.link[next]} (07 §4), never exposes {@code telecom}, and staff management stays
 * Admin-only. Fake data only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + PractitionerApiTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + PractitionerApiTest.TestSecurityConfig.AUDIENCE,
      "shp.paging.token-key=test-only-page-token-key-32-bytes-minimum!!"
    })
@Testcontainers
@Import(PractitionerApiTest.TestSecurityConfig.class)
class PractitionerApiTest {
  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv(
              "GLIBC_TUNABLES",
              "glibc.pthread.rseq=1") // kernel 6.19-7.0.13: SERVER-121912, see MongoTestBase
          .withReplicaSet();

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired Environment env;
  @Autowired MongoTemplate mongo;

  @TestConfiguration
  static class TestSecurityConfig {
    static final String ISSUER = "https://idp.test/realms/shp";
    static final String AUDIENCE = "shp-api";
    static final byte[] KEY =
        "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);

    @Bean
    @Primary
    JwtDecoder testJwtDecoder(ShpAuthProperties props, Clock clock) {
      var d =
          NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
              .macAlgorithm(MacAlgorithm.HS256)
              .build();
      d.setJwtValidator(ShpJwt.validator(props, clock));
      return d;
    }
  }

  @BeforeEach
  void reset() {
    for (String c :
        new String[] {
          PractitionerRepository.PRACTITIONERS,
          PractitionerRepository.PRACTITIONER_ROLES,
          MongoMessaging.RESOURCE_HISTORY,
          MongoMessaging.OUTBOX,
          "identity_links"
        }) {
      mongo.getCollection(c).deleteMany(new Document());
    }
    link("admin-1", "Practitioner/p-admin");
    link("patient-1", "Patient/p-1");
    link("nurse-1", "Practitioner/p-nurse");
  }

  void link(String subject, String fhirUser) {
    mongo.save(
        new Document("_id", subject).append("fhirUser", fhirUser).append("disabled", false),
        "identity_links");
  }

  static String practitioner(String staffCode) {
    return """
    {
      "resourceType": "Practitioner",
      "identifier": [ { "system": "urn:shp:practitioner", "value": "%s" } ],
      "active": true,
      "name": [ { "text": "Dr. Le Minh Chau (test)", "prefix": [ "Dr." ] } ],
      "telecom": [ { "system": "email", "value": "chau.le@example.test", "use": "work" } ],
      "gender": "female",
      "qualification": [ { "code": { "text": "Specialist Level I, Cardiology" } } ]
    }
    """
        .formatted(staffCode);
  }

  private String token(String subject, List<String> roles) {
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(TestSecurityConfig.ISSUER)
              .subject(subject)
              .audience(TestSecurityConfig.AUDIENCE)
              .claim("roles", roles)
              .claim("amr", List.of("pwd", "mfa"))
              .issueTime(Date.from(now.minusSeconds(60)))
              .notBeforeTime(Date.from(now.minusSeconds(60)))
              .expirationTime(Date.from(now.plusSeconds(600)));
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
      jwt.sign(new MACSigner(TestSecurityConfig.KEY));
      return jwt.serialize();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private HttpResponse<String> send(HttpRequest req) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
  }

  private URI url(String path) {
    return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
  }

  private HttpResponse<String> get(String path, String subject, String role) throws Exception {
    var req =
        HttpRequest.newBuilder(url(path))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(subject, List.of(role)))
            .GET()
            .build();
    return send(req);
  }

  private HttpResponse<String> create(String body, String subject, String role) throws Exception {
    var req =
        HttpRequest.newBuilder(url("/fhir/Practitioner"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(subject, List.of(role)))
            .header(HttpHeaders.CONTENT_TYPE, FhirJson.MEDIA_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return send(req);
  }

  @Test
  void adminCreatesAPractitionerWithEtagAndLocation() throws Exception {
    var res = create(practitioner("NV-0101"), "admin-1", "admin");
    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(res.headers().firstValue(HttpHeaders.ETAG)).contains("W/\"1\"");
    assertThat(res.headers().firstValue(HttpHeaders.LOCATION))
        .hasValueSatisfying(l -> assertThat(l).contains("/_history/1"));

    JsonNode body = JSON.readTree(res.body());
    assertThat(body.path("resourceType").asString()).isEqualTo("Practitioner");
    assertThat(body.path("meta").path("versionId").asString()).isEqualTo("1");
  }

  @Test
  void onlyAnAdminCanManageStaff() throws Exception {
    assertThat(create(practitioner("NV-0102"), "nurse-1", "nurse").statusCode()).isEqualTo(403);
    assertThat(create(practitioner("NV-0103"), "patient-1", "patient").statusCode()).isEqualTo(403);
    assertThat(create(practitioner("NV-0104"), "admin-1", "admin").statusCode()).isEqualTo(201);
  }

  @Test
  void directoryNeverReturnsTelecomAndPagesWithANextLink() throws Exception {
    create(practitioner("NV-0105"), "admin-1", "admin");
    create(practitioner("NV-0106"), "admin-1", "admin");

    var first = get("/fhir/Practitioner?_count=1", "patient-1", "patient");
    assertThat(first.statusCode()).isEqualTo(200);
    JsonNode page = JSON.readTree(first.body());
    assertThat(page.path("entry").size()).isEqualTo(1);
    assertThat(page.path("entry").get(0).path("resource").has("telecom")).isFalse();

    String next = nextLink(page);
    assertThat(next).contains("_page_token=").doesNotContain("_offset");

    var second =
        send(
            HttpRequest.newBuilder(URI.create(next))
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + token("patient-1", List.of("patient")))
                .GET()
                .build());
    assertThat(second.statusCode()).isEqualTo(200);
    JsonNode secondPage = JSON.readTree(second.body());
    assertThat(secondPage.path("entry").size()).isEqualTo(1);
    assertThat(nextLink(secondPage)).isNull();
    assertThat(secondPage.path("entry").get(0).path("resource").path("id").asString())
        .isNotEqualTo(page.path("entry").get(0).path("resource").path("id").asString());
  }

  @Test
  void readByIdAlsoDropsTelecom() throws Exception {
    var created = JSON.readTree(create(practitioner("NV-0107"), "admin-1", "admin").body());
    String id = created.path("id").asString();

    var res = get("/fhir/Practitioner/" + id, "patient-1", "patient");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(res.body()).has("telecom")).isFalse();
  }

  @Test
  void anInvalidPageTokenIsA400() throws Exception {
    assertThat(get("/fhir/Practitioner?_page_token=garbage", "patient-1", "patient").statusCode())
        .isEqualTo(400);
    assertThat(get("/fhir/Practitioner?_page_token=a.b", "patient-1", "patient").statusCode())
        .isEqualTo(400);
  }

  private static String nextLink(JsonNode bundle) {
    List<String> links = new ArrayList<>();
    for (JsonNode link : bundle.path("link")) {
      if ("next".equals(link.path("relation").asString())) {
        links.add(link.path("url").asString());
      }
    }
    assertThat(links).hasSizeLessThanOrEqualTo(1);
    return links.isEmpty() ? null : links.getFirst();
  }
}
