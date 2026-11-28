package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.bson.Document;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.ClinicalApiTestBase;
import org.shp.clinical.infra.PatientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** CLN-06 / C4, C5, C7 on real HTTP, real authorization and a real MongoDB. Fake data only. */
class PatientApiTest extends ClinicalApiTestBase {
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String FHIR = "application/fhir+json";

  @Autowired PatientRepository patients;

  String an; // patient An
  String binh; // patient Binh
  // The identity resolver caches subject -> link for 60 s, so every test uses fresh subjects.
  String subAn;
  String subBinh;
  String subRel;
  String subNurse;
  String subDoc;
  String subAdmin;
  String cam; // the relative's own record

  @BeforeEach
  void fixtures() {
    an = newPatient("Nguyen", "Van An", "+84901000001", "1985-03-14");
    binh = newPatient("Tran", "Thi Binh", "+84901000002", "1990-07-21");
    subAn = "acc-an-" + an;
    subBinh = "acc-binh-" + binh;
    subRel = "acc-rel-" + an;
    subNurse = "nurse-" + an;
    subDoc = "doc-" + an;
    subAdmin = "admin-" + an;
    link(subAn, "Patient/" + an);
    link(subBinh, "Patient/" + binh);
    cam = newPatient("Pham", "Minh Cam", "+84901000003", "1975-11-02");
    link(subRel, "Patient/" + cam);
    link(subNurse, "Practitioner/prac-nurse");
    link(subDoc, "Practitioner/prac-doc");
    link(subAdmin, "Practitioner/prac-admin");
  }

  String newPatient(String family, String given, String phone, String birthDate) {
    Patient p = new Patient();
    p.addName()
        .setUse(NameUse.OFFICIAL)
        .setText(family + " " + given)
        .setFamily(family)
        .addGiven(given);
    p.addTelecom().setSystem(ContactPointSystem.PHONE).setValue(phone);
    p.setGender(AdministrativeGender.MALE);
    p.setBirthDateElement(new DateType(birthDate));
    return patients.create(p, "seed", null).id();
  }

  String get(String id, String token, int expectedStatus) throws Exception {
    var r = call("GET", "/fhir/Patient/" + id, token, null);
    assertThat(r.statusCode()).isEqualTo(expectedStatus);
    return r.body();
  }

  /** The current resource of {@code id}, changed by {@code edit}, ready to PUT. */
  String edited(String id, Consumer<ObjectNode> edit) throws Exception {
    ObjectNode node = (ObjectNode) JSON.readTree(get(id, token(subNurse, "nurse"), 200));
    edit.accept(node);
    return JSON.writeValueAsString(node);
  }

  HttpResponse<String> put(String id, String token, String version, String body) throws Exception {
    return version == null
        ? call("PUT", "/fhir/Patient/" + id, token, body, "Content-Type", FHIR)
        : call(
            "PUT", "/fhir/Patient/" + id, token, body, "Content-Type", FHIR, "If-Match", version);
  }

  static void setPhone(ObjectNode patient, String phone) {
    ((ObjectNode) patient.get("telecom").get(0)).put("value", phone);
  }

  String code(HttpResponse<String> r) {
    JsonNode issue = JSON.readTree(r.body()).get("issue").get(0);
    return issue.get("details").get("coding").get(0).get("code").asString();
  }

  List<Document> denied() {
    return auditRecords().stream().filter(a -> "denied".equals(a.getString("outcome"))).toList();
  }

  // ---------- C4 read ----------

  @Test
  void aPatientReadsOwnRecordWithItsVersion() throws Exception {
    var r = call("GET", "/fhir/Patient/" + an, token(subAn, "patient"), null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("ETag")).hasValue("W/\"1\"");
    assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith(FHIR);
    JsonNode body = JSON.readTree(r.body());
    assertThat(body.get("id").asString()).isEqualTo(an);
    assertThat(body.get("meta").get("versionId").asString()).isEqualTo("1");
    assertThat(body.get("identifier").get(0).get("value").asString()).matches("BN-\\d{4}-\\d{6}");
  }

  @Test
  void anotherPatientsRecordIsA404ThatLooksLikeAMissingOne() throws Exception {
    var other = call("GET", "/fhir/Patient/" + binh, token(subAn, "patient"), null);
    var missing =
        call(
            "GET",
            "/fhir/Patient/0192f8a4-6c1e-7b2a-9f00-000000000000",
            token(subAn, "patient"),
            null);
    assertThat(other.statusCode()).isEqualTo(404);
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(code(other)).isEqualTo("not-found");
    assertThat(other.body()).doesNotContain("Tran").doesNotContain("BN-");
    // the probe is audited as a denial
    assertThat(denied())
        .anySatisfy(a -> {
          assertThat(a.get("actor", Document.class).getString("subject")).isEqualTo(subAn);
          assertThat(a.getString("resource")).isEqualTo("Patient/" + binh);
        });
  }

  @Test
  void staffAccessFollowsTheMatrix() throws Exception {
    assertThat(call("GET", "/fhir/Patient/" + an, null, null).statusCode()).isEqualTo(401);
    get(an, token(subNurse, "nurse"), 200);

    var noCare = call("GET", "/fhir/Patient/" + an, token(subDoc, "doctor"), null);
    assertThat(noCare.statusCode()).isEqualTo(403);
    assertThat(code(noCare)).isEqualTo("no-care-relationship");

    mongo.save(
        new Document("_id", "prac-doc|" + an)
            .append("validUntil", Date.from(Instant.now().plus(30, ChronoUnit.DAYS))),
        "care_relationships");
    get(an, token(subDoc, "doctor"), 200);

    get(an, token(subAdmin, "admin"), 403);
  }

  @Test
  void aDoctorWithAnExpiredCareRelationshipOrAnActiveBreakGlassSession() throws Exception {
    mongo.save(
        new Document("_id", "prac-doc|" + an)
            .append("validUntil", Date.from(Instant.now().minus(1, ChronoUnit.DAYS))),
        "care_relationships");
    get(an, token(subDoc, "doctor"), 403);

    mongo.save(
        new Document("practitionerId", "prac-doc")
            .append("patientId", an)
            .append("expiresAt", Date.from(Instant.now().plus(30, ChronoUnit.MINUTES))),
        "break_glass_sessions");
    get(an, token(subDoc, "doctor"), 200);
  }

  @Test
  void aLinkedAccountReadsButOnlyAGuardianMayEdit() throws Exception {
    mongo.save(
        new Document("accountSub", subRel)
            .append("patientId", an)
            .append("status", "active")
            .append("scope", "booking")
            .append("relationship", "proxy")
            .append("expiresAt", Date.from(Instant.now().plus(30, ChronoUnit.DAYS))),
        "profile_links");
    String t = token(subRel, "patient");
    get(an, t, 200);
    String body = edited(an, p -> setPhone(p, "+84901000099"));
    assertThat(put(an, t, "W/\"1\"", body).statusCode()).isEqualTo(403);

    mongo.updateFirst(
        Query.query(Criteria.where("accountSub").is(subRel)),
        new Update().set("relationship", "guardian"),
        "profile_links");
    assertThat(put(an, t, "W/\"1\"", body).statusCode()).isEqualTo(200);
  }

  /**
   * The newest audit record of {@code subject} on {@code Patient/<id>} with {@code outcome}; the
   * success record of a request is written after its response, so wait for it.
   */
  Document auditOf(String subject, String id, String outcome) {
    var found = new Document[1];
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          var matching =
              auditRecords().stream()
                  .filter(a -> subject.equals(a.get("actor", Document.class).getString("subject")))
                  .filter(a -> ("Patient/" + id).equals(a.getString("resource")))
                  .filter(a -> outcome.equals(a.getString("outcome")))
                  .toList();
          assertThat(matching).isNotEmpty();
          found[0] = matching.getLast();
        });
    return found[0];
  }

  @Test
  void accessThroughALinkIsAuditedOnBehalfOfTheManagedProfile() throws Exception {
    mongo.save(
        new Document("accountSub", subRel)
            .append("patientId", an)
            .append("status", "active")
            .append("scope", "full")
            .append("relationship", "guardian")
            .append("expiresAt", Date.from(Instant.now().plus(30, ChronoUnit.DAYS))),
        "profile_links");
    String t = token(subRel, "patient");
    get(an, t, 200);
    Document read = auditOf(subRel, an, "success");
    assertThat(read.getString("onBehalfOf")).isEqualTo("Patient/" + an);
    assertThat(read.getString("relationship")).isEqualTo("guardian");
    assertThat(read.get("actor", Document.class).getString("fhirUser")).isEqualTo("Patient/" + cam);
    assertThat(read.getString("patientId")).isEqualTo(an);

    // A proxy may read but not edit; the refused edit is still audited on the profile's behalf.
    mongo.updateFirst(
        Query.query(Criteria.where("accountSub").is(subRel)),
        new Update().set("relationship", "proxy"),
        "profile_links");
    String body = edited(an, p -> setPhone(p, "+84901000098"));
    assertThat(put(an, t, "W/\"1\"", body).statusCode()).isEqualTo(403);
    Document refused = auditOf(subRel, an, "denied");
    assertThat(refused.getString("onBehalfOf")).isEqualTo("Patient/" + an);
    assertThat(refused.getString("relationship")).isEqualTo("proxy");
    assertThat(refused.get("actor", Document.class).getString("subject")).isEqualTo(subRel);

    get(an, token(subAn, "patient"), 200);
    Document own = auditOf(subAn, an, "success");
    assertThat(own).doesNotContainKey("onBehalfOf").doesNotContainKey("relationship");
  }

  /**
   * The matrix allows a guardian's PUT (PAT-UPD-GUARDIAN) but the field rule refuses a name change;
   * the service writes that denial itself and it keeps the managed profile. So does a refused
   * reveal on a read the link allowed. The owner's own refused edit carries neither key.
   */
  @Test
  void refusalsAfterAnAllowedLinkedRequestAreAuditedOnBehalfOfTheManagedProfile() throws Exception {
    mongo.save(
        new Document("accountSub", subRel)
            .append("patientId", an)
            .append("status", "active")
            .append("scope", "full")
            .append("relationship", "guardian")
            .append("expiresAt", Date.from(Instant.now().plus(30, ChronoUnit.DAYS))),
        "profile_links");
    String guardian = token(subRel, "patient");
    Consumer<ObjectNode> rename = p -> ((ObjectNode) p.get("name").get(0)).put("family", "Changed");

    var refused = put(an, guardian, "W/\"1\"", edited(an, rename));
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(code(refused)).isEqualTo("forbidden");
    Document field = deniedOf(subRel, an, "update");
    assertThat(field.getString("reason")).isEqualTo("field-not-allowed");
    assertThat(field.getString("onBehalfOf")).isEqualTo("Patient/" + an);
    assertThat(field.getString("relationship")).isEqualTo("guardian");
    assertThat(field.get("actor", Document.class).getString("subject")).isEqualTo(subRel);
    assertThat(field.get("actor", Document.class).getString("fhirUser"))
        .isEqualTo("Patient/" + cam);
    assertThat(field.getString("patientId")).isEqualTo(an);

    var reveal = call("GET", "/fhir/Patient/" + an + "?reveal=national-id", guardian, null);
    assertThat(reveal.statusCode()).isEqualTo(403);
    Document revealRefused = deniedOf(subRel, an, "read");
    assertThat(revealRefused.getString("onBehalfOf")).isEqualTo("Patient/" + an);
    assertThat(revealRefused.getString("relationship")).isEqualTo("guardian");
    assertThat(revealRefused.get("actor", Document.class).getString("fhirUser"))
        .isEqualTo("Patient/" + cam);

    // Staff keep their own type: a doctor may read with a care relationship but not reveal
    // (PAT-REVEAL-NUR is the only reveal rule, so no nurse is ever refused here).
    mongo.save(
        new Document("_id", "prac-doc|" + an)
            .append("validUntil", Date.from(Instant.now().plus(30, ChronoUnit.DAYS))),
        "care_relationships");
    var doctorReveal =
        call("GET", "/fhir/Patient/" + an + "?reveal=national-id", token(subDoc, "doctor"), null);
    assertThat(doctorReveal.statusCode()).isEqualTo(403);
    Document staffRefused = deniedOf(subDoc, an, "read");
    assertThat(staffRefused.get("actor", Document.class).getString("fhirUser"))
        .isEqualTo("Practitioner/prac-doc");
    assertThat(staffRefused).doesNotContainKey("onBehalfOf").doesNotContainKey("relationship");

    var own = put(an, token(subAn, "patient"), "W/\"1\"", edited(an, rename));
    assertThat(own.statusCode()).isEqualTo(403);
    Document ownRefused = deniedOf(subAn, an, "update");
    assertThat(ownRefused.getString("reason")).isEqualTo("field-not-allowed");
    assertThat(ownRefused).doesNotContainKey("onBehalfOf").doesNotContainKey("relationship");
    assertThat(mongo.findById(an, Document.class, "patients").getInteger("versionId")).isEqualTo(1);
  }

  /** The single denied record of {@code subject} on {@code Patient/<id>} with this audit action. */
  Document deniedOf(String subject, String id, String action) {
    var found = new Document[1];
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          var matching =
              denied().stream()
                  .filter(a -> subject.equals(a.get("actor", Document.class).getString("subject")))
                  .filter(a -> ("Patient/" + id).equals(a.getString("resource")))
                  .filter(a -> action.equals(a.getString("action")))
                  .toList();
          assertThat(matching).hasSize(1);
          found[0] = matching.getFirst();
        });
    return found[0];
  }

  @Test
  void anExpiredOrRevokedLinkIsNotAccess() throws Exception {
    mongo.save(
        new Document("accountSub", subRel)
            .append("patientId", an)
            .append("status", "active")
            .append("scope", "full")
            .append("expiresAt", Date.from(Instant.now().minus(1, ChronoUnit.DAYS))),
        "profile_links");
    get(an, token(subRel, "patient"), 404);
  }

  // ---------- C5 update ----------

  @Test
  void aPatientEditsContactDetailsAndEveryEditIsAVersion() throws Exception {
    String t = token(subAn, "patient");
    var first = put(an, t, "W/\"1\"", edited(an, p -> setPhone(p, "+84901000010")));
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.headers().firstValue("ETag")).hasValue("W/\"2\"");
    assertThat(JSON.readTree(first.body()).get("meta").get("versionId").asString()).isEqualTo("2");

    var second = put(an, t, "W/\"2\"", edited(an, p -> setPhone(p, "+84901000011")));
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(second.headers().firstValue("ETag")).hasValue("W/\"3\"");

    Document stored = mongo.findById(an, Document.class, "patients");
    assertThat(stored.getInteger("versionId")).isEqualTo(3);
    assertThat(stored.get("search", Document.class).getString("phone")).isEqualTo("+84901000011");
    assertThat(
            mongo.find(
                Query.query(Criteria.where("id").is(an)), Document.class, "resource_history"))
        .hasSize(3);
  }

  @Test
  void aPatientEditingTheirNameOrOtherFixedFieldsIsRefused() throws Exception {
    String t = token(subAn, "patient");
    List<Consumer<ObjectNode>> forbidden = new ArrayList<>();
    forbidden.add(p ->
        ((ObjectNode) p.get("name").get(0)).put("family", "Changed").put("text", "Changed Van An"));
    forbidden.add(p -> p.put("birthDate", "1980-01-01"));
    forbidden.add(p -> p.put("gender", "female"));
    forbidden.add(p -> ((ObjectNode) p.get("identifier").get(0)).put("value", "BN-2026-999999"));
    forbidden.add(p -> p.put("active", false));
    for (var change : forbidden) {
      var r = put(an, t, "W/\"1\"", edited(an, change));
      assertThat(r.statusCode()).isEqualTo(403);
      assertThat(code(r)).isEqualTo("forbidden");
    }
    Document stored = mongo.findById(an, Document.class, "patients");
    assertThat(stored.getInteger("versionId")).isEqualTo(1);
    assertThat(denied())
        .filteredOn(a -> ("Patient/" + an).equals(a.getString("resource")))
        .hasSizeGreaterThanOrEqualTo(forbidden.size());
  }

  @Test
  void aPatientCannotEditAnotherPatientsRecord() throws Exception {
    var r =
        put(
            binh,
            token(subAn, "patient"),
            "W/\"1\"",
            edited(binh, p -> setPhone(p, "+84901000077")));
    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(mongo.findById(binh, Document.class, "patients").getInteger("versionId"))
        .isEqualTo(1);
  }

  @Test
  void aNurseEditsTheNameAndSearchFieldsFollow() throws Exception {
    var r =
        put(
            an,
            token(subNurse, "nurse"),
            "W/\"1\"",
            edited(
                an,
                p ->
                    ((ObjectNode) p.get("name").get(0))
                        .put("family", "Le")
                        .put("text", "Le Van An")));
    assertThat(r.statusCode()).isEqualTo(200);
    Document search = mongo.findById(an, Document.class, "patients").get("search", Document.class);
    assertThat(search.getList("nameTokens", String.class)).containsExactly("le", "van", "an");
  }

  @Test
  void theMrnAndMergeStateCannotBeChangedByAnyone() throws Exception {
    String t = token(subNurse, "nurse");
    var mrn =
        put(
            an,
            t,
            "W/\"1\"",
            edited(
                an, p -> ((ObjectNode) p.get("identifier").get(0)).put("value", "BN-2026-999999")));
    assertThat(mrn.statusCode()).isEqualTo(400);
    var inactive = put(an, t, "W/\"1\"", edited(an, p -> p.put("active", false)));
    assertThat(inactive.statusCode()).isEqualTo(400);
  }

  @Test
  void preconditionAndBodyErrors() throws Exception {
    String t = token(subAn, "patient");
    String body = edited(an, p -> setPhone(p, "+84901000020"));
    assertThat(put(an, t, null, body).statusCode()).isEqualTo(428);
    assertThat(put(an, t, "W/\"7\"", body).statusCode()).isEqualTo(412);
    assertThat(put(an, t, "W/\"1\"", edited(an, p -> p.put("id", binh))).statusCode())
        .isEqualTo(400);
    var badPhone = put(an, t, "W/\"1\"", edited(an, p -> setPhone(p, "12345")));
    assertThat(badPhone.statusCode()).isEqualTo(400);
    assertThat(badPhone.body()).doesNotContain("12345");
    assertThat(put(an, t, "W/\"1\"", "{ not json").statusCode()).isEqualTo(400);
    assertThat(mongo.findById(an, Document.class, "patients").getInteger("versionId")).isEqualTo(1);
  }

  @Test
  void twoEditsOnTheSameVersionLeaveExactlyOneWinner() throws Exception {
    String t = token(subAn, "patient");
    var pool = Executors.newFixedThreadPool(2);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      String body = edited(an, p -> setPhone(p, "+8490100003" + System.nanoTime() % 10));
      results.add(pool.submit(() -> put(an, t, "W/\"1\"", body).statusCode()));
    }
    List<Integer> codes = new ArrayList<>();
    for (var f : results) {
      codes.add(f.get());
    }
    pool.shutdown();
    assertThat(codes).containsExactlyInAnyOrder(200, 412);
    assertThat(mongo.findById(an, Document.class, "patients").getInteger("versionId")).isEqualTo(2);
  }

  // ---------- C7 history ----------

  @Test
  void aRecordEditedTwiceHasThreeVersionsInTheHistory() throws Exception {
    String patient = token(subAn, "patient");
    put(an, patient, "W/\"1\"", edited(an, p -> setPhone(p, "+84901000040")));
    put(an, patient, "W/\"2\"", edited(an, p -> setPhone(p, "+84901000041")));

    var r = call("GET", "/fhir/Patient/" + an + "/_history", token(subNurse, "nurse"), null);
    assertThat(r.statusCode()).isEqualTo(200);
    JsonNode bundle = JSON.readTree(r.body());
    assertThat(bundle.get("resourceType").asString()).isEqualTo("Bundle");
    assertThat(bundle.get("type").asString()).isEqualTo("history");
    assertThat(bundle.get("total").asInt()).isEqualTo(3);
    JsonNode entries = bundle.get("entry");
    assertThat(entries).hasSize(3);
    assertThat(entries.get(0).get("resource").get("meta").get("versionId").asString())
        .isEqualTo("3");
    assertThat(entries.get(2).get("resource").get("meta").get("versionId").asString())
        .isEqualTo("1");
    assertThat(entries.get(2).get("request").get("method").asString()).isEqualTo("POST");
    assertThat(entries.get(0).get("request").get("method").asString()).isEqualTo("PUT");
    assertThat(entries.get(1).get("response").get("etag").asString()).isEqualTo("W/\"2\"");
    assertThat(entries.get(1).get("resource").get("telecom").get(0).get("value").asString())
        .isEqualTo("+84901000040");
  }

  @Test
  void historyIsForNursesOnly() throws Exception {
    String path = "/fhir/Patient/" + an + "/_history";
    assertThat(call("GET", path, token(subAn, "patient"), null).statusCode()).isEqualTo(403);
    assertThat(call("GET", path, token(subBinh, "patient"), null).statusCode()).isEqualTo(404);
    assertThat(call("GET", path, token(subDoc, "doctor"), null).statusCode()).isEqualTo(403);
    assertThat(call("GET", path, token(subAdmin, "admin"), null).statusCode()).isEqualTo(403);
    assertThat(call("GET", path, null, null).statusCode()).isEqualTo(401);
    assertThat(
            call(
                    "GET",
                    "/fhir/Patient/0192f8a4-6c1e-7b2a-9f00-000000000000/_history",
                    token(subNurse, "nurse"),
                    null)
                .statusCode())
        .isEqualTo(404);
  }

  @Test
  void everyAccessIsAudited() throws Exception {
    get(an, token(subAn, "patient"), 200);
    put(an, token(subAn, "patient"), "W/\"1\"", edited(an, p -> setPhone(p, "+84901000050")));
    // the success record of a request is written after its response, so wait for it
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(auditRecords())
                .extracting(a -> a.getString("action"))
                .contains("read", "update"));
    List<Document> audit = auditRecords();
    assertThat(audit)
        .allSatisfy(
            a -> assertThat(a.toString()).doesNotContain("+84901000050").doesNotContain("Nguyen"));
  }
}
