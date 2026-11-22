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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * CLN-11 against a real MongoDB: {@code GET /api/v1/me} (C2), link and lock accounts (C21), the
 * internal identity lookup (09 §4). Tokens are signed with a local key; the identity layer, the
 * matrix and the audit sink are the real shared ones.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Import(IdentityApiTest.TestBeans.class)
@Testcontainers
class IdentityApiTest {
  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv(
              "GLIBC_TUNABLES",
              "glibc.pthread.rseq=1") // kernel 6.19-7.0.13: SERVER-121912, see MongoTestBase
          .withReplicaSet();

  static final byte[] KEY =
      "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String PATIENT_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
  static final String DOCTOR_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
  static final String NURSE_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03";
  static final String ADMIN_ID = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e04";

  /** A clock the test moves forward to cross the 60-second identity cache. */
  static final class MovableClock extends Clock {
    private volatile Instant now = Instant.now();

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @TestConfiguration
  static class TestBeans {
    @Bean
    MovableClock movableClock() {
      return new MovableClock();
    }

    @Bean
    JwtDecoder testJwtDecoder() {
      // No clock validators: the movable clock only exists to age the identity cache.
      return NimbusJwtDecoder.withSecretKey(new javax.crypto.spec.SecretKeySpec(KEY, "HmacSHA256"))
          .macAlgorithm(MacAlgorithm.HS256)
          .build();
    }
  }

  @Autowired Environment env;
  @Autowired MongoTemplate mongo;
  @Autowired MovableClock clock;

  @BeforeEach
  void seed() {
    for (String c :
        List.of(
            "identity_links",
            "patients",
            "practitioners",
            "practitioner_roles",
            "profile_links",
            "outbox")) {
      mongo.remove(new org.springframework.data.mongodb.core.query.Query(), c);
    }
    mongo.insert(
        new Document("_id", PATIENT_ID).append("search", new Document("active", true)), "patients");
    practitioner(DOCTOR_ID, "doctor");
    practitioner(NURSE_ID, "nurse");
    practitioner(ADMIN_ID, null);
    account("admin-sub", "Practitioner/" + ADMIN_ID, List.of("admin"), false);
    account("pat-sub", "Patient/" + PATIENT_ID, List.of("patient"), false);
  }

  private void practitioner(String id, String roleCode) {
    mongo.insert(
        new Document("_id", id).append("search", new Document("active", true)), "practitioners");
    if (roleCode != null) {
      mongo.insert(
          new Document("_id", "role-" + id)
              .append(
                  "search",
                  new Document("practitionerId", id)
                      .append("code", roleCode)
                      .append("active", true)),
          "practitioner_roles");
    }
  }

  private void account(String sub, String fhirUser, List<String> roles, boolean disabled) {
    mongo.insert(
        new Document("_id", sub)
            .append("fhirUser", fhirUser)
            .append("roles", roles)
            .append("disabled", disabled),
        "identity_links");
  }

  private void profileLink(
      String sub, String patientId, String relationship, String scope, String status, Instant exp) {
    var d =
        new Document("accountSub", sub)
            .append("patientId", patientId)
            .append("relationship", relationship)
            .append("scope", scope)
            .append("status", status)
            .append("createdAt", Date.from(clock.instant()));
    d.append("expiresAt", exp == null ? null : Date.from(exp));
    mongo.insert(d, "profile_links");
  }

  // ---------------------------------------------------------------- helpers

  static String token(String sub, String... roles) {
    return serviceToken(sub, null, roles);
  }

  /** A token as the realm issues it: aud shp-api, roles, and azp for a service client. */
  static String serviceToken(String sub, String azp, String... roles) {
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer("https://idp.test/realms/shp")
              .subject(sub)
              .audience("shp-api")
              .claim("roles", List.of(roles))
              .claim("azp", azp)
              .expirationTime(Date.from(now.plusSeconds(600)));
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
      jwt.sign(new MACSigner(KEY));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  HttpResponse<String> call(String method, String path, String token, String body)
      throws Exception {
    return call(method, path, token, body, null);
  }

  HttpResponse<String> call(String method, String path, String token, String body, String ifMatch)
      throws Exception {
    var b =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    if (ifMatch != null) {
      b.header("If-Match", ifMatch);
    }
    try (var c = HttpClient.newHttpClient()) {
      return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  /** PUT with If-Match = the account's current version (07 §3). */
  HttpResponse<String> put(String subject, String token, String body) throws Exception {
    var stored = mongo.findById(subject, Document.class, "identity_links");
    long version = stored == null ? 1L : stored.get("versionId", 1L);
    return call("PUT", "/api/v1/admin/users/" + subject, token, body, "W/\"" + version + "\"");
  }

  static JsonNode json(HttpResponse<String> r) {
    return JSON.readTree(r.body());
  }

  static String admin() {
    return token("admin-sub", "admin");
  }

  static String link(String sub, String fhirUser, String... roles) {
    return "{\"subject\":\"%s\",\"fhirUser\":\"%s\",\"roles\":%s}"
        .formatted(
            sub,
            fhirUser,
            "["
                + String.join(
                    ",", java.util.Arrays.stream(roles).map(r -> "\"" + r + "\"").toList())
                + "]");
  }

  // ---------------------------------------------------------------- C2 /me

  @Test
  void meReturnsTheLinkedProfileAndRoles() throws Exception {
    var r = call("GET", "/api/v1/me", token("pat-sub", "patient"), null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(json(r).get("fhirUser").asString()).isEqualTo("Patient/" + PATIENT_ID);
    assertThat(json(r).get("roles").get(0).asString()).isEqualTo("patient");
    assertThat(json(r).get("linkedProfiles").size()).isZero();
  }

  @Test
  void meForAnUnlinkedAccountHasNoFhirUser() throws Exception {
    var r = call("GET", "/api/v1/me", token("new-sub", "patient"), null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(json(r).get("fhirUser").isNull()).isTrue();
  }

  @Test
  void meWithoutTokenIs401AndForALockedAccountIs403() throws Exception {
    assertThat(call("GET", "/api/v1/me", null, null).statusCode()).isEqualTo(401);
    account("locked-sub", "Patient/p-locked", List.of("patient"), true);
    assertThat(call("GET", "/api/v1/me", token("locked-sub", "patient"), null).statusCode())
        .isEqualTo(403);
  }

  // ---------------------------------------------------------------- C21 link

  @Test
  void adminLinksADoctorToAPractitionerWithAMatchingRole() throws Exception {
    var r =
        call(
            "POST",
            "/api/v1/admin/users",
            admin(),
            link("doc-sub", "Practitioner/" + DOCTOR_ID, "doctor"));
    assertThat(r.statusCode()).isEqualTo(201);
    assertThat(json(r).get("disabled").asBoolean()).isFalse();
    var stored = mongo.findById("doc-sub", Document.class, "identity_links");
    assertThat(stored.getString("fhirUser")).isEqualTo("Practitioner/" + DOCTOR_ID);
    assertThat(stored.getString("createdBy")).isEqualTo("admin-sub");
    assertThat(call("GET", "/api/v1/me", token("doc-sub", "doctor"), null).statusCode())
        .isEqualTo(200);
  }

  @Test
  void aRoleThatDoesNotMatchThePractitionerRoleIs422() throws Exception {
    // the Practitioner is a doctor, the Admin asks for nurse
    var r =
        call(
            "POST",
            "/api/v1/admin/users",
            admin(),
            link("x-sub", "Practitioner/" + DOCTOR_ID, "nurse"));
    assertThat(r.statusCode()).isEqualTo(422);
    assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("problem+json");
    assertThat(json(r).get("code").asString()).isEqualTo("role-mismatch");
    assertThat(mongo.findById("x-sub", Document.class, "identity_links")).isNull();
  }

  @Test
  void aPractitionerWithNoPractitionerRoleCannotBeLinkedAsADoctor() throws Exception {
    var r =
        call(
            "POST",
            "/api/v1/admin/users",
            admin(),
            link("x-sub", "Practitioner/" + ADMIN_ID, "doctor"));
    assertThat(r.statusCode()).isEqualTo(422);
  }

  @Test
  void patientAndStaffRolesCannotBeMixedAcrossProfileTypes() throws Exception {
    assertThat(
            call(
                    "POST",
                    "/api/v1/admin/users",
                    admin(),
                    link("a", "Practitioner/" + DOCTOR_ID, "patient"))
                .statusCode())
        .isEqualTo(422);
    assertThat(
            call(
                    "POST",
                    "/api/v1/admin/users",
                    admin(),
                    link("b", "Patient/" + PATIENT_ID, "doctor"))
                .statusCode())
        .isEqualTo(422);
  }

  @Test
  void aProfileOrSubjectThatIsAlreadyLinkedIs409() throws Exception {
    assertThat(
            call(
                    "POST",
                    "/api/v1/admin/users",
                    admin(),
                    link("other", "Patient/" + PATIENT_ID, "patient"))
                .statusCode())
        .isEqualTo(409);
    assertThat(
            call(
                    "POST",
                    "/api/v1/admin/users",
                    admin(),
                    link("pat-sub", "Practitioner/" + DOCTOR_ID, "doctor"))
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void aPractitionerAlreadyLinkedToOneAccountCannotBeLinkedToAnother() throws Exception {
    String fhirUser = "Practitioner/" + DOCTOR_ID;
    assertThat(
            call("POST", "/api/v1/admin/users", admin(), link("acc-a", fhirUser, "doctor"))
                .statusCode())
        .isEqualTo(201);
    assertThat(
            call("POST", "/api/v1/admin/users", admin(), link("acc-b", fhirUser, "doctor"))
                .statusCode())
        .isEqualTo(409);
    assertThat(mongo.findById("acc-b", Document.class, "identity_links")).isNull();
  }

  @Test
  void aMissingProfileIs404AndMalformedInputIs400() throws Exception {
    assertThat(
            call(
                    "POST",
                    "/api/v1/admin/users",
                    admin(),
                    link("z", "Practitioner/no-such-id", "doctor"))
                .statusCode())
        .isEqualTo(404);
    for (String bad :
        List.of(
            link("", "Patient/" + PATIENT_ID, "patient"),
            link("z", "Device/1", "patient"),
            link("z", "Patient/" + PATIENT_ID, "superuser"),
            "{\"subject\":\"z\",\"fhirUser\":\"Patient/1\",\"roles\":[]}",
            "not json")) {
      assertThat(call("POST", "/api/v1/admin/users", admin(), bad).statusCode())
          .as(bad)
          .isEqualTo(400);
    }
  }

  @Test
  void onlyAnAdminMayManageUsersAndTheDenialIsAudited() throws Exception {
    mongo.remove(new org.springframework.data.mongodb.core.query.Query(), "outbox");
    for (String role : List.of("patient", "doctor", "nurse", "dpo")) {
      String sub = role.equals("patient") ? "pat-sub" : "s-" + role;
      if (!role.equals("patient")) {
        account(sub, "Practitioner/" + DOCTOR_ID + "-" + role, List.of(role), false);
      }
      String t = token(sub, role);
      assertThat(call("GET", "/api/v1/admin/users", t, null).statusCode()).as(role).isEqualTo(403);
      assertThat(put("pat-sub", t, "{\"disabled\":true}").statusCode()).as(role).isEqualTo(403);
    }
    assertThat(mongo.getCollection("outbox").countDocuments()).isPositive();
    assertThat(mongo.findById("pat-sub", Document.class, "identity_links").getBoolean("disabled"))
        .isFalse();
  }

  @Test
  void listPagesThroughAccountsInSubjectOrder() throws Exception {
    var first = json(call("GET", "/api/v1/admin/users?_count=1", admin(), null));
    assertThat(first.get("items").size()).isEqualTo(1);
    assertThat(first.get("items").get(0).get("subject").asString()).isEqualTo("admin-sub");
    String token = first.get("nextPageToken").asString();
    var second =
        json(call("GET", "/api/v1/admin/users?_count=1&_page_token=" + token, admin(), null));
    assertThat(second.get("items").get(0).get("subject").asString()).isEqualTo("pat-sub");
    assertThat(second.get("nextPageToken").isNull()).isTrue();
    assertThat(call("GET", "/api/v1/admin/users?_count=0", admin(), null).statusCode())
        .isEqualTo(400);
    assertThat(call("GET", "/api/v1/admin/users?_page_token=***", admin(), null).statusCode())
        .isEqualTo(400);
  }

  // ---------------------------------------------------------------- C21 lock (AC-08.1)

  @Test
  void aLockIsRejectedAtTheLatestWhenTheSixtySecondIdentityCacheExpires() throws Exception {
    String patient = token("pat-sub", "patient");
    assertThat(call("GET", "/api/v1/me", patient, null).statusCode()).isEqualTo(200); // cached

    var lock = put("pat-sub", admin(), "{\"disabled\":true}");
    assertThat(lock.statusCode()).isEqualTo(200);
    assertThat(json(lock).get("disabled").asBoolean()).isTrue();

    clock.advance(Duration.ofSeconds(30)); // still inside the cache window
    assertThat(call("GET", "/api/v1/me", patient, null).statusCode()).isEqualTo(200);
    clock.advance(Duration.ofSeconds(31)); // 61 s after the lookup: the same, unexpired token
    assertThat(call("GET", "/api/v1/me", patient, null).statusCode()).isEqualTo(403);

    var unlock = put("pat-sub", admin(), "{\"disabled\":false}");
    assertThat(json(unlock).get("disabled").asBoolean()).isFalse();
    clock.advance(Duration.ofSeconds(61));
    assertThat(call("GET", "/api/v1/me", patient, null).statusCode()).isEqualTo(200);
  }

  @Test
  void lockNeedsTheCurrentVersionInIfMatch() throws Exception {
    String path = "/api/v1/admin/users/pat-sub";
    assertThat(call("PUT", path, admin(), "{\"disabled\":true}").statusCode()).isEqualTo(428);
    assertThat(call("PUT", path, admin(), "{\"disabled\":true}", "W/\"7\"").statusCode())
        .isEqualTo(412);
    var ok = call("PUT", path, admin(), "{\"disabled\":true}", "W/\"1\"");
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(ok.headers().firstValue("ETag")).contains("W/\"2\"");
    // the same, now stale, version cannot overwrite the newer state
    assertThat(call("PUT", path, admin(), "{\"disabled\":false}", "W/\"1\"").statusCode())
        .isEqualTo(412);
    assertThat(mongo.findById("pat-sub", Document.class, "identity_links").getBoolean("disabled"))
        .isTrue();
  }

  @Test
  void lockRejectsAMissingFlagAndAnUnknownSubject() throws Exception {
    assertThat(put("pat-sub", admin(), "{}").statusCode()).isEqualTo(400);
    assertThat(put("nobody", admin(), "{\"disabled\":true}").statusCode()).isEqualTo(404);
  }

  // ---------------------------------------------------------------- CLN-33 linkedProfiles

  @Test
  void meAndTheInternalLookupListOnlyActiveUnexpiredLinks() throws Exception {
    Instant now = clock.instant();
    profileLink("pat-sub", "child-1", "guardian", "full", "active", now.plus(Duration.ofDays(300)));
    profileLink("pat-sub", "parent-1", "proxy", "booking", "active", null);
    profileLink(
        "pat-sub", "revoked-1", "proxy", "booking", "revoked", now.plus(Duration.ofDays(9)));
    profileLink(
        "pat-sub", "pending-1", "proxy", "records", "pending", now.plus(Duration.ofDays(9)));
    profileLink("pat-sub", "expired-1", "proxy", "booking", "expired", now.minusSeconds(5));
    // still marked active, but past expiresAt: ignored without waiting for a job
    profileLink("pat-sub", "stale-1", "proxy", "booking", "active", now.minusSeconds(1));
    profileLink("other-sub", "other-1", "proxy", "booking", "active", null);

    var me = json(call("GET", "/api/v1/me", token("pat-sub", "patient"), null));
    assertThat(me.get("fhirUser").asString()).isEqualTo("Patient/" + PATIENT_ID);
    var items = me.get("linkedProfiles");
    assertThat(items.size()).isEqualTo(2);
    assertThat(items.get(0).get("patientId").asString()).isEqualTo("child-1");
    assertThat(items.get(0).get("relationship").asString()).isEqualTo("guardian");
    assertThat(items.get(0).get("scope").asString()).isEqualTo("full");
    assertThat(items.get(0).get("expiresAt").isNull()).isFalse();
    assertThat(items.get(1).get("patientId").asString()).isEqualTo("parent-1");
    assertThat(items.get(1).get("scope").asString()).isEqualTo("booking");
    assertThat(items.get(1).get("expiresAt").isNull()).isTrue();

    String svc = serviceToken("svc-scheduling-sa", "svc-scheduling", "internal.clinical");
    var internal = json(call("GET", "/internal/v1/identity/pat-sub", svc, null));
    assertThat(internal.get("linkedProfiles").size()).isEqualTo(2);
    assertThat(internal.get("linkedProfiles").get(0).get("patientId").asString())
        .isEqualTo("child-1");
    assertThat(internal.get("fhirUser").asString()).isEqualTo("Patient/" + PATIENT_ID);
  }

  @Test
  void aRevokedLinkDisappearsAtOnceAndALinkThatReachesItsExpiryIsIgnored() throws Exception {
    String svc = serviceToken("svc-scheduling-sa", "svc-scheduling", "internal.clinical");
    profileLink("pat-sub", "rel-1", "proxy", "booking", "active", clock.instant().plusSeconds(120));
    assertThat(
            json(call("GET", "/internal/v1/identity/pat-sub", svc, null))
                .get("linkedProfiles")
                .size())
        .isEqualTo(1);

    clock.advance(Duration.ofSeconds(121));
    assertThat(
            json(call("GET", "/internal/v1/identity/pat-sub", svc, null))
                .get("linkedProfiles")
                .size())
        .isZero();

    profileLink("pat-sub", "rel-2", "proxy", "booking", "active", null);
    assertThat(
            json(call("GET", "/api/v1/me", token("pat-sub", "patient"), null))
                .get("linkedProfiles")
                .size())
        .isEqualTo(1);
    mongo.updateMulti(
        new org.springframework.data.mongodb.core.query.Query(),
        new org.springframework.data.mongodb.core.query.Update().set("status", "revoked"),
        "profile_links");
    assertThat(
            json(call("GET", "/api/v1/me", token("pat-sub", "patient"), null))
                .get("linkedProfiles")
                .size())
        .isZero();
    assertThat(
            json(call("GET", "/internal/v1/identity/pat-sub", svc, null))
                .get("linkedProfiles")
                .size())
        .isZero();
  }

  @Test
  void anAccountCannotBeLinkedTwiceToTheSameProfile() {
    profileLink("pat-sub", "rel-1", "proxy", "booking", "active", null);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> profileLink("pat-sub", "rel-1", "guardian", "full", "active", null))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  // ---------------------------------------------------------------- internal identity API

  @Test
  void internalLookupReturnsTheLinkAndTheLockStateImmediately() throws Exception {
    String svc = serviceToken("svc-scheduling-sa", "svc-scheduling", "internal.clinical");
    var r = call("GET", "/internal/v1/identity/pat-sub", svc, null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(json(r).get("fhirUser").asString()).isEqualTo("Patient/" + PATIENT_ID);
    assertThat(json(r).get("roles").get(0).asString()).isEqualTo("patient");
    assertThat(json(r).get("disabled").asBoolean()).isFalse();

    put("pat-sub", admin(), "{\"disabled\":true}");
    assertThat(
            json(call("GET", "/internal/v1/identity/pat-sub", svc, null))
                .get("disabled")
                .asBoolean())
        .isTrue();
  }

  @Test
  void internalLookupIs404ForAnUnlinkedSubjectAndDecodesTheSubject() throws Exception {
    String svc = serviceToken("svc-document-sa", "svc-document", "internal.clinical");
    assertThat(call("GET", "/internal/v1/identity/nobody", svc, null).statusCode()).isEqualTo(404);
    account("auth|a b", "Patient/p-x", List.of("patient"), false);
    var r = call("GET", "/internal/v1/identity/auth%7Ca%20b", svc, null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(json(r).get("fhirUser").asString()).isEqualTo("Patient/p-x");
  }

  @Test
  void internalLookupNeedsAWorkloadToken() throws Exception {
    assertThat(call("GET", "/internal/v1/identity/pat-sub", null, null).statusCode())
        .isEqualTo(401);
    // a user token, even an Admin's, is not a workload identity
    assertThat(call("GET", "/internal/v1/identity/pat-sub", admin(), null).statusCode())
        .isEqualTo(403);
    assertThat(
            call(
                    "GET",
                    "/internal/v1/identity/pat-sub",
                    serviceToken("svc-x-sa", "svc-scheduling", "internal.scheduling"),
                    null)
                .statusCode())
        .isEqualTo(403);
    // right role, but not a known caller client
    assertThat(
            call(
                    "GET",
                    "/internal/v1/identity/pat-sub",
                    serviceToken("s", "svc-other", "internal.clinical"),
                    null)
                .statusCode())
        .isEqualTo(403);
    assertThat(
            call(
                    "GET",
                    "/internal/v1/identity/pat-sub",
                    serviceToken("s", null, "internal.clinical"),
                    null)
                .statusCode())
        .isEqualTo(403);
    assertThat(
            call(
                    "POST",
                    "/internal/v1/identity/pat-sub",
                    serviceToken("s", "svc-audit", "internal.clinical"),
                    "{}")
                .statusCode())
        .isEqualTo(400);
  }
}
