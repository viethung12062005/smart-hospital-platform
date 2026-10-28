package org.shp.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.InMemoryAuditSink;
import org.shp.web.auth.IdentityLink;
import org.shp.web.authz.UnprotectedEndpoints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

@SpringBootTest(
    classes = TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.application.name=clinical",
      "shp.auth.authority=" + TestApp.ISSUER,
      "shp.auth.audience=" + TestApp.AUDIENCE
    })
class WebTest {
  static final String PATIENT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
  static final String DOCTOR = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";

  @Autowired Environment env;
  @Autowired TestApp.FakeLinks links;
  @Autowired InMemoryAuditSink audit;
  @Autowired ApplicationContext ctx;

  @BeforeEach
  void reset() {
    links.links.clear();
    audit.clear();
    TestApp.CARE.clear();
    links.links.put("pat", new IdentityLink("Patient", PATIENT, false));
    links.links.put("doc", new IdentityLink("Practitioner", DOCTOR, false));
    links.links.put(
        "nurse", new IdentityLink("Practitioner", "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03", false));
  }

  HttpResponse<String> get(String path, String token, String lang) throws Exception {
    var b =
        HttpRequest.newBuilder(
            URI.create("http://localhost:" + env.getProperty("local.server.port") + path));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    if (lang != null) {
      b.header("Accept-Language", lang);
    }
    try (var c = HttpClient.newHttpClient()) {
      return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  HttpResponse<String> get(String path, String token) throws Exception {
    return get(path, token, null);
  }

  static JsonNode json(HttpResponse<String> r) {
    return CloudEvents.JSON.readTree(r.body());
  }

  static String patient() {
    return TestApp.token(List.of("patient"), "pat", null);
  }

  static String doctor() {
    return TestApp.token(List.of("doctor"), "doc", List.of("mfa"));
  }

  static String nurse() {
    return TestApp.token(List.of("nurse"), "nurse", List.of("mfa"));
  }

  // ---------- xác thực + danh tính ----------
  @Test
  void validLinkedPatientReachesEndpointWithPrincipal() throws Exception {
    var r = get("/api/v1/me", patient());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(json(r).get("fhirUserId").asString()).isEqualTo(PATIENT);
  }

  @Test
  void missingTokenIs401ProblemJson() throws Exception {
    var r = get("/api/v1/me", null);
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.headers().firstValue("Content-Type"))
        .hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
    assertThat(json(r).get("code").asString()).isEqualTo("unauthenticated");
    assertThat(r.headers().firstValue("X-Request-Id")).isPresent();
  }

  @Test
  void wrongAudienceWrongSignatureAndExpiredTokensAre401() throws Exception {
    for (String t :
        List.of(
            TestApp.token(
                List.of("patient"), "pat", null, "other-api", Duration.ofMinutes(10), TestApp.KEY),
            TestApp.token(
                List.of("patient"),
                "pat",
                null,
                TestApp.AUDIENCE,
                Duration.ofMinutes(10),
                TestApp.OTHER_KEY),
            TestApp.token(
                List.of("patient"),
                "pat",
                null,
                TestApp.AUDIENCE,
                Duration.ofMinutes(-2),
                TestApp.KEY))) {
      assertThat(get("/api/v1/me", t).statusCode()).isEqualTo(401);
    }
  }

  @Test
  void staffWithoutMfaIsRejectedAndWithMfaAccepted() throws Exception {
    assertThat(get("/api/v1/me", TestApp.token(List.of("doctor"), "doc", null)).statusCode())
        .isEqualTo(401);
    assertThat(
            get("/api/v1/me", TestApp.token(List.of("doctor"), "doc", List.of("pwd", "mfa")))
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void patientDoesNotNeedMfa() throws Exception {
    assertThat(get("/api/v1/me", patient()).statusCode()).isEqualTo(200);
  }

  @Test
  void tokenWithoutKnownRoleIs403() throws Exception {
    var r = get("/api/v1/me", TestApp.token(List.of("superuser"), "pat", null));
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(json(r).get("code").asString()).isEqualTo("forbidden");
  }

  @Test
  void unlinkedAccountIs403ExceptOnRegistration() throws Exception {
    String t = TestApp.token(List.of("patient"), "new-user", null);
    assertThat(get("/api/v1/me", t).statusCode()).isEqualTo(403);
    var reg = get("/api/v1/registration", t);
    assertThat(reg.statusCode()).isEqualTo(200);
    assertThat(json(reg).get("linked").asBoolean()).isFalse();
  }

  @Test
  void disabledAccountIs403EvenOnRegistration() throws Exception {
    links.links.put("locked", new IdentityLink("Patient", PATIENT, true));
    String t = TestApp.token(List.of("patient"), "locked", null);
    assertThat(get("/api/v1/me", t).statusCode()).isEqualTo(403);
    assertThat(get("/api/v1/registration", t).statusCode()).isEqualTo(403);
  }

  @Test
  void roleClaimIsCaseInsensitiveAndUnknownValuesDropped() throws Exception {
    var b = json(get("/api/v1/me", TestApp.token(List.of("PATIENT", "wizard"), "pat", null)));
    assertThat(b.get("roles").size()).isEqualTo(1);
  }

  @Test
  void healthAndInfoArePublic() throws Exception {
    assertThat(get("/health/ready", null).statusCode()).isEqualTo(200);
    assertThat(get("/info", null).statusCode()).isEqualTo(200);
  }

  // ---------- lỗi chuẩn ----------
  @Test
  void apiErrorIsProblemJsonWithVietnameseMessage() throws Exception {
    var r = get("/api/v1/slot", patient());
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(json(r).get("code").asString()).isEqualTo("slot-unavailable");
    assertThat(json(r).get("detail").asString()).containsIgnoringCase("khung giờ");
  }

  @Test
  void fhirErrorIsOperationOutcome() throws Exception {
    var r = get("/fhir/Patient/x", patient(), "en");
    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(r.headers().firstValue("Content-Type"))
        .hasValueSatisfying(v -> assertThat(v).startsWith("application/fhir+json"));
    var issue = json(r).get("issue").get(0);
    assertThat(issue.get("code").asString()).isEqualTo("not-found");
    assertThat(issue.get("details").get("coding").get(0).get("code").asString())
        .isEqualTo("not-found");
    assertThat(issue.get("diagnostics").asString()).isEqualTo("Not found.");
  }

  @Test
  void rateLimitedCarriesRetryAfter() throws Exception {
    var r = get("/api/v1/limited", patient());
    assertThat(r.statusCode()).isEqualTo(429);
    assertThat(r.headers().firstValue("Retry-After")).hasValue("30");
  }

  @Test
  void unhandledExceptionIs500WithoutLeakingDetails() throws Exception {
    var r = get("/api/v1/boom", patient());
    assertThat(r.statusCode()).isEqualTo(500);
    assertThat(r.body())
        .contains("internal-error")
        .doesNotContain("Nguyen")
        .doesNotContain("0901234567");
  }

  @Test
  void unknownRouteIs404InStandardFormat() throws Exception {
    var r = get("/api/v1/nope", patient());
    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(json(r).get("code").asString()).isEqualTo("not-found");
  }

  // ---------- phân quyền + audit ----------
  @Test
  void patientReadsOwnRecordAndTheAccessIsAudited() throws Exception {
    assertThat(get("/api/v1/patients/" + PATIENT, patient()).statusCode()).isEqualTo(200);
    AuditRecord rec = single();
    assertThat(rec.outcome()).isEqualTo("success");
    assertThat(rec.action()).isEqualTo("read");
    assertThat(rec.resource()).isEqualTo("Patient/" + PATIENT);
    assertThat(rec.purposeOfUse()).isEqualTo("PATRQT");
    assertThat(rec.service()).isEqualTo("clinical");
    assertThat(rec.patientId()).isEqualTo(UUID.fromString(PATIENT));
    assertThat(EventSchemas.validate(rec.toCloudEvent(java.time.Clock.systemUTC()))).isEmpty();
  }

  @Test
  void patientReadingSomeoneElseIs403AndDeniedIsAudited() throws Exception {
    assertThat(get("/api/v1/patients/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5eff", patient()).statusCode())
        .isEqualTo(403);
    AuditRecord rec = single();
    assertThat(rec.outcome()).isEqualTo("denied");
    assertThat(rec.reason()).isEqualTo("unmet-own");
  }

  @Test
  void doctorNeedsACareRelationship() throws Exception {
    var denied = get("/api/v1/patients/" + PATIENT, doctor());
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(json(denied).get("code").asString()).isEqualTo("no-care-relationship");
    TestApp.CARE.add(DOCTOR + "|" + PATIENT);
    assertThat(get("/api/v1/patients/" + PATIENT, doctor()).statusCode()).isEqualTo(200);
    assertThat(audit.records())
        .extracting(AuditRecord::outcome)
        .containsExactly("denied", "success");
    assertThat(audit.records().get(1).purposeOfUse()).isEqualTo("TREAT");
  }

  @Test
  void denyAsNotFoundHidesExistenceButStillAudits() throws Exception {
    assertThat(get("/api/v1/hidden/" + PATIENT, doctor()).statusCode()).isEqualTo(404);
    assertThat(single().outcome()).isEqualTo("denied");
  }

  @Test
  void adminCanNeverReadClinicalData() throws Exception {
    links.links.put(
        "adm", new IdentityLink("Practitioner", "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e09", false));
    assertThat(
            get(
                    "/api/v1/patients/" + PATIENT,
                    TestApp.token(List.of("admin"), "adm", List.of("mfa")))
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void searchAuditRecordsParameterNamesButNeverValues() throws Exception {
    assertThat(get("/api/v1/patients?name=Nguyen%20Van%20A&phone=0901234567", nurse()).statusCode())
        .isEqualTo(200);
    AuditRecord rec = single();
    assertThat(rec.resource()).isEqualTo("Patient?name&phone");
    assertThat(rec.action()).isEqualTo("search");
    assertThat(CloudEvents.JSON.writeValueAsString(rec))
        .doesNotContain("Nguyen")
        .doesNotContain("0901234567");
  }

  @Test
  void businessErrorAfterAuthorizationIsStillASuccessAccessRecord() throws Exception {
    assertThat(get("/api/v1/audited-conflict", nurse()).statusCode()).isEqualTo(409);
    assertThat(single().outcome()).isEqualTo("success");
  }

  @Test
  void unexpectedFailureIsAuditedAsErrorWithoutLeaking() throws Exception {
    var r = get("/api/v1/audited-boom", nurse());
    assertThat(r.statusCode()).isEqualTo(500);
    assertThat(single().outcome()).isEqualTo("error");
    assertThat(r.body()).doesNotContain("hunter2");
  }

  @Test
  void unauthenticatedRequestNeverReachesHandlerOrAudit() throws Exception {
    assertThat(get("/api/v1/patients/" + PATIENT, null).statusCode()).isEqualTo(401);
    assertThat(audit.records()).isEmpty();
  }

  @Test
  void scannerFindsEndpointsWithoutADeclaredRule() {
    assertThat(UnprotectedEndpoints.find(ctx)).containsExactly("/api/v1/unprotected");
  }

  private AuditRecord single() {
    assertThat(audit.records()).hasSize(1);
    return audit.records().getFirst();
  }
}
