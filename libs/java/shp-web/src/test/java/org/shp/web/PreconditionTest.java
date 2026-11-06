package org.shp.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvents;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.InMemoryAuditSink;
import org.shp.web.auth.IdentityLink;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/** Header bắt buộc If-Match / Idempotency-Key (428, 412) và ETag trên response (07 §3, SHR-05). */
@SpringBootTest(
    classes = TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.application.name=clinical",
      "shp.auth.authority=" + TestApp.ISSUER,
      "shp.auth.audience=" + TestApp.AUDIENCE
    })
class PreconditionTest {
  static final String IDEM = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e10";
  static final String CANCEL = "/fhir/Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e20/$cancel";

  @Autowired Environment env;
  @Autowired TestApp.FakeLinks links;
  @Autowired InMemoryAuditSink audit;

  @BeforeEach
  void reset() {
    links.links.clear();
    audit.clear();
    links.links.put("pat", new IdentityLink("Patient", WebTest.PATIENT, false));
    links.links.put(
        "nurse", new IdentityLink("Practitioner", "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03", false));
  }

  HttpResponse<String> send(String method, String path, String token, String... headers)
      throws Exception {
    var b =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + env.getProperty("local.server.port") + path))
            .method(
                method,
                "GET".equals(method)
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString("{}"))
            .header("Authorization", "Bearer " + token);
    if (headers.length > 0) {
      b.headers(headers);
    }
    try (var c = HttpClient.newHttpClient()) {
      return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  /** Mã lỗi từ OperationOutcome (/fhir) hoặc Problem Details. */
  static String code(HttpResponse<String> r) {
    JsonNode body = CloudEvents.JSON.readTree(r.body());
    return body.has("issue")
        ? body.get("issue").get(0).get("details").get("coding").get(0).get("code").asString()
        : body.get("code").asString();
  }

  // ---------- If-Match trên PUT ----------
  @Test
  void putWithoutIfMatchIs428() throws Exception {
    var r = send("PUT", "/fhir/Patient/p1", WebTest.patient());
    assertThat(r.statusCode()).isEqualTo(428);
    assertThat(code(r)).isEqualTo("precondition-required");
    assertThat(r.headers().firstValue("ETag")).isEmpty();
  }

  @Test
  void putWithCurrentVersionSucceedsAndReturnsTheNewETag() throws Exception {
    var r = send("PUT", "/fhir/Patient/p1", WebTest.patient(), "If-Match", "W/\"3\"");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("ETag")).hasValue("W/\"4\"");
  }

  @Test
  void staleOrMalformedIfMatchIs412() throws Exception {
    for (String ifMatch : List.of("W/\"2\"", "\"3\"", "*", "W/\"abc\"", "W/\"3\", W/\"4\"")) {
      var r = send("PUT", "/fhir/Patient/p1", WebTest.patient(), "If-Match", ifMatch);
      assertThat(r.statusCode()).as(ifMatch).isEqualTo(412);
      assertThat(code(r)).isEqualTo("version-conflict");
    }
  }

  @Test
  void multipleIfMatchLinesAreRejectedEvenIfTheFirstOneMatches() throws Exception {
    // RFC 9110 cho phép lặp header; chỉ đọc dòng đầu thì "W/\"2\"" phía sau bị bỏ qua và request
    // ghi
    // đè lên bản mới hơn vẫn qua
    var r =
        send(
            "PUT",
            "/fhir/Patient/p1",
            WebTest.patient(),
            "If-Match",
            "W/\"3\"",
            "If-Match",
            "W/\"2\"");
    assertThat(r.statusCode()).isEqualTo(412);
    assertThat(code(r)).isEqualTo("version-conflict");
  }

  // ---------- Idempotency-Key + If-Match trên thao tác đổi trạng thái ----------
  @Test
  void stateChangeWithoutIdempotencyKeyIs428() throws Exception {
    var r = send("POST", CANCEL, WebTest.nurse(), "If-Match", "W/\"3\"");
    assertThat(r.statusCode()).isEqualTo(428);
    assertThat(code(r)).isEqualTo("idempotency-key-required");
    assertThat(audit.records()).isEmpty();
  }

  @Test
  void stateChangeWithoutIfMatchIs428() throws Exception {
    var r = send("POST", CANCEL, WebTest.nurse(), "Idempotency-Key", IDEM);
    assertThat(r.statusCode()).isEqualTo(428);
    assertThat(code(r)).isEqualTo("precondition-required");
    assertThat(audit.records()).isEmpty();
  }

  @Test
  void invalidIdempotencyKeyIs400() throws Exception {
    var r =
        send("POST", CANCEL, WebTest.nurse(), "Idempotency-Key", "short", "If-Match", "W/\"3\"");
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(code(r)).isEqualTo("validation-failed");
  }

  @Test
  void stateChangeWithBothHeadersReachesHandlerAndIsAudited() throws Exception {
    var r = send("POST", CANCEL, WebTest.nurse(), "Idempotency-Key", IDEM, "If-Match", "W/\"3\"");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("ETag")).hasValue("W/\"4\"");
    assertThat(CloudEvents.JSON.readTree(r.body()).get("idempotencyKey").asString())
        .isEqualTo(IDEM);
    assertThat(audit.records())
        .singleElement()
        .extracting(AuditRecord::outcome)
        .isEqualTo("success");
  }

  @Test
  void headersAreCheckedBeforeAuthorization() throws Exception {
    // Bệnh nhân không có dữ kiện "own" → bị từ chối; nhưng thiếu header thì 428 trước, không audit.
    var missing = send("POST", CANCEL, WebTest.patient());
    assertThat(missing.statusCode()).isEqualTo(428);
    assertThat(audit.records()).isEmpty();
    var denied =
        send("POST", CANCEL, WebTest.patient(), "Idempotency-Key", IDEM, "If-Match", "W/\"3\"");
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(audit.records())
        .singleElement()
        .extracting(AuditRecord::outcome)
        .isEqualTo("denied");
  }

  // ---------- ETag ----------
  @Test
  void etagComesFromVersionedDtoOrFhirMetaVersionId() throws Exception {
    assertThat(send("GET", "/api/v1/uploads/u1", WebTest.patient()).headers().firstValue("ETag"))
        .hasValue("W/\"7\"");
    assertThat(send("GET", "/fhir/Observation/o1", WebTest.patient()).headers().firstValue("ETag"))
        .hasValue("W/\"2\"");
  }

  @Test
  void responsesWithoutAVersionHaveNoETag() throws Exception {
    var me = send("GET", "/api/v1/me", WebTest.patient());
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(me.headers().firstValue("ETag")).isEmpty();
    var list = send("GET", "/api/v1/patients", WebTest.nurse());
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.headers().firstValue("ETag")).isEmpty();
  }

  // ---------- đơn vị ----------
  @Test
  void ifMatchFormatAndParse() {
    assertThat(ETags.format(12)).isEqualTo("W/\"12\"");
    assertThat(ETags.parseIfMatch(" W/\"12\" ")).isEqualTo(12);
    for (String missing : new String[] {null, "", " "}) {
      assertThatThrownBy(() -> ETags.parseIfMatch(missing))
          .isInstanceOfSatisfying(
              ShpException.class,
              e -> assertThat(e.info().code()).isEqualTo("precondition-required"));
    }
    assertThatThrownBy(() -> ETags.parseIfMatch("W/\"1234567890123456789\""))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("version-conflict"));
  }

  @Test
  void accessorsFailLoudlyWhenTheEndpointDidNotDeclareTheHeader() {
    var req = new MockHttpServletRequest();
    assertThatThrownBy(() -> PreconditionInterceptor.expectedVersion(req))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> PreconditionInterceptor.idempotencyKey(req))
        .isInstanceOf(IllegalStateException.class);
  }
}
