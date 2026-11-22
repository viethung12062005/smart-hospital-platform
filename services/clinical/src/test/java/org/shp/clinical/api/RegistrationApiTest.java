package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.clinical.ClinicalApiTestBase;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** CLN-05 / C1: registration over HTTP on a real MongoDB. Fake data only. */
class RegistrationApiTest extends ClinicalApiTestBase {
  static final JsonMapper JSON = JsonMapper.builder().build();

  static String body(String phone, String birthDate) {
    return """
    {
      "name": { "family": "Nguyen", "given": ["Van", "An"] },
      "phone": "%s",
      "email": "an.nguyen@example.test",
      "gender": "male",
      "birthDate": "%s",
      "address": { "city": "Hanoi", "district": "Cau Giay" },
      "language": "vi"
    }
    """
        .formatted(phone, birthDate);
  }

  JsonNode json(HttpResponse<String> r) {
    return JSON.readTree(r.body());
  }

  @Test
  void registrationCreatesThePatientAndLinksTheAccount() throws Exception {
    var r =
        call(
            "POST",
            "/api/v1/registration",
            token("acc-1", "patient"),
            body("0901234567", "1985-03-14"));

    assertThat(r.statusCode()).isEqualTo(201);
    String patientId = json(r).get("patientId").asString();
    String mrn = json(r).get("mrn").asString();
    assertThat(mrn).matches("BN-\\d{4}-\\d{6}");

    Document stored = mongo.findById(patientId, Document.class, "patients");
    assertThat(stored.get("search", Document.class).getString("mrn")).isEqualTo(mrn);
    assertThat(stored.get("search", Document.class).getString("phone")).isEqualTo("+84901234567");
    assertThat(stored.getInteger("versionId")).isEqualTo(1);
    Document link = mongo.findById("acc-1", Document.class, "identity_links");
    assertThat(link.getString("fhirUser")).isEqualTo("Patient/" + patientId);
    assertThat(link.getBoolean("disabled")).isFalse();
    assertThat(link.getString("issuer")).isEqualTo(ISSUER);
    assertThat(mongo.count(new Query(), "resource_history")).isEqualTo(1);
  }

  @Test
  void theNewlyLinkedAccountCanReadItsRecordButNotRegisterAgain() throws Exception {
    var first =
        call(
            "POST",
            "/api/v1/registration",
            token("acc-2", "patient"),
            body("0901234568", "1990-01-02"));
    assertThat(first.statusCode()).isEqualTo(201);

    var again =
        call(
            "POST",
            "/api/v1/registration",
            token("acc-2", "patient"),
            body("0901234569", "1991-01-02"));
    assertThat(again.statusCode()).isEqualTo(409);
    assertThat(json(again).get("code").asString()).isEqualTo("already-registered");
    assertThat(mongo.count(new Query(), "patients")).isEqualTo(1);
  }

  @Test
  void invalidInputIsA400WithoutEchoingTheValue() throws Exception {
    String t = token("acc-3", "patient");
    String[] bad = {
      body("12345", "1985-03-14"), // not a Vietnamese number
      body("0901234567", "2999-01-01"), // in the future
      body("0901234567", "1800-01-01"), // older than 120 years
      body("0901234567", "not-a-date"),
      body("0901234567", "1985-03-14").replace("\"male\"", "\"robot\""),
      body("0901234567", "1985-03-14").replace("\"Nguyen\"", "\"\""),
      body("0901234567", "1985-03-14").replace("an.nguyen@example.test", "not-an-email"),
      "{ \"name\": ", // malformed JSON
    };
    for (String b : bad) {
      var r = call("POST", "/api/v1/registration", t, b);
      assertThat(r.statusCode()).as(b).isEqualTo(400);
      assertThat(json(r).get("code").asString()).isEqualTo("validation-failed");
      assertThat(r.body()).doesNotContain("0901234567").doesNotContain("an.nguyen");
    }
    assertThat(mongo.count(new Query(), "patients")).isZero();
    assertThat(mongo.count(new Query(), "identity_links")).isZero();
  }

  @Test
  void samePhoneAndBirthDateIsRefusedWithoutLeakingTheExistingRecord() throws Exception {
    var first =
        call(
            "POST",
            "/api/v1/registration",
            token("acc-4", "patient"),
            body("0901234567", "1985-03-14"));
    String existingMrn = json(first).get("mrn").asString();
    String existingId = json(first).get("patientId").asString();

    String other =
        body("090 123 4567", "1985-03-14")
            .replace("Nguyen", "Pham")
            .replace("\"Van\", \"An\"", "\"Lan\"");
    var r = call("POST", "/api/v1/registration", token("acc-5", "patient"), other);

    assertThat(r.statusCode()).isEqualTo(422);
    assertThat(json(r).get("code").asString()).isEqualTo("duplicate-patient-suspected");
    assertThat(r.body())
        .doesNotContain(existingMrn)
        .doesNotContain(existingId)
        .doesNotContain("Nguyen");
    assertThat(mongo.count(new Query(), "patients")).isEqualTo(1);
    assertThat(mongo.findById("acc-5", Document.class, "identity_links")).isNull();
  }

  @Test
  void concurrentRegistrationsOfTheSameAccountCreateOneRecord() throws Exception {
    String t = token("acc-6", "patient");
    var pool = Executors.newFixedThreadPool(6);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      int n = i;
      results.add(
          pool.submit(() ->
              call("POST", "/api/v1/registration", t, body("09012345" + (10 + n), "1985-03-14"))
                  .statusCode()));
    }
    List<Integer> codes = new ArrayList<>();
    for (var f : results) {
      codes.add(f.get());
    }
    pool.shutdown();
    assertThat(codes).containsOnly(201, 409).filteredOn(c -> c == 201).hasSize(1);
    assertThat(mongo.count(new Query(), "patients")).isEqualTo(1);
  }

  @Test
  void onlyAPatientAccountMayRegisterAndATokenIsRequired() throws Exception {
    assertThat(
            call("POST", "/api/v1/registration", null, body("0901234567", "1985-03-14"))
                .statusCode())
        .isEqualTo(401);

    link("doc-1", "Practitioner/p-1");
    var doctor =
        call(
            "POST",
            "/api/v1/registration",
            token("doc-1", "doctor"),
            body("0901234567", "1985-03-14"));
    assertThat(doctor.statusCode()).isEqualTo(403);
    assertThat(mongo.count(new Query(), "patients")).isZero();
  }

  @Test
  void everyDecisionIsAudited() throws Exception {
    call(
        "POST",
        "/api/v1/registration",
        token("acc-7", "patient"),
        body("0901234567", "1985-03-14"));
    link("doc-2", "Practitioner/p-2");
    call(
        "POST", "/api/v1/registration", token("doc-2", "doctor"), body("0901234568", "1985-03-14"));

    // the success record of a request is written after its response, so wait for it
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(auditRecords()).hasSize(2));
    List<Document> audit = auditRecords();
    assertThat(audit).hasSize(2);
    Document allowed =
        audit.stream()
            .filter(a -> "acc-7".equals(a.get("actor", Document.class).getString("subject")))
            .findFirst()
            .orElseThrow();
    assertThat(allowed.getString("outcome")).isEqualTo("success");
    Document denied =
        audit.stream()
            .filter(a -> "doc-2".equals(a.get("actor", Document.class).getString("subject")))
            .findFirst()
            .orElseThrow();
    assertThat(denied.getString("outcome")).isEqualTo("denied");
    assertThat(audit.toString()).doesNotContain("Nguyen").doesNotContain("0901234567");
  }
}
