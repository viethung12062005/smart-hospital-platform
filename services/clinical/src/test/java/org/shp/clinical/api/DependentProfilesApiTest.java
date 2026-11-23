package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CLN-34 / C22, C23 (US-076; AC-DP.1–AC-DP.3) on real HTTP and MongoDB: a guardian lists the
 * profiles they manage and adds a person under 16. Fake data only.
 */
class DependentProfilesApiTest extends ClinicalApiTestBase {
  static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired PatientRepository patients;

  String self;
  String account;
  String nurse;

  @BeforeEach
  void fixtures() {
    self = newPatient("Le", "Thi Mai", "+84901000051", "1988-05-05");
    account = "acc-dp-" + System.nanoTime();
    nurse = "nurse-dp-" + System.nanoTime();
    link(account, "Patient/" + self);
    link(nurse, "Practitioner/prac-nurse-dp");
  }

  String newPatient(String family, String given, String phone, String birthDate) {
    Patient p = new Patient();
    p.addName()
        .setUse(NameUse.OFFICIAL)
        .setText(family + " " + given)
        .setFamily(family)
        .addGiven(given);
    p.addTelecom().setSystem(ContactPointSystem.PHONE).setValue(phone);
    p.setGender(AdministrativeGender.FEMALE);
    p.setBirthDateElement(new DateType(birthDate));
    return patients.create(p, "User/test", null).id();
  }

  static String child(String given, String birthDate, Boolean declaration) {
    return """
    { "name": { "family": "Le", "given": ["%s"] }, "birthDate": "%s", "gender": "male"%s }
    """
        .formatted(
            given, birthDate, declaration == null ? "" : ", \"declaration\": " + declaration);
  }

  static String yearsAgo(int years, int plusDays) {
    return LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        .minusYears(years)
        .plusDays(plusDays)
        .toString();
  }

  HttpResponse<String> create(String body) throws Exception {
    return call("POST", "/api/v1/me/profiles/dependents", token(account, "patient"), body);
  }

  long links() {
    return mongo.count(Query.query(Criteria.where("accountSub").is(account)), "profile_links");
  }

  /** AC-DP.2: valid information under 16 with the declaration → record created, linked. */
  @Test
  void aChildUnder16IsCreatedAndLinkedToTheGuardian() throws Exception {
    var res = create(child("Van Nam", yearsAgo(8, 0), true));
    assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
    JsonNode body = JSON.readTree(res.body());
    assertThat(body.get("relationship").asString()).isEqualTo("guardian");
    assertThat(body.get("scope").asString()).isEqualTo("full");
    String childId = body.get("patientId").asString();
    Patient c = PatientRepository.toFhir(patients.find(childId).orElseThrow());
    assertThat(c.getNameFirstRep().getText()).isEqualTo("Le Van Nam");
    assertThat(c.getTelecomFirstRep().getValue()).isEqualTo("+84901000051"); // the guardian's
    assertThat(links()).isEqualTo(1);

    JsonNode list =
        JSON.readTree(call("GET", "/api/v1/me/profiles", token(account, "patient"), null).body());
    assertThat(list).hasSize(2);
    assertThat(list.get(0).get("relationship").asString()).isEqualTo("self");
    assertThat(list.get(1).get("patientId").asString()).isEqualTo(childId);
  }

  /** AC-DP.1: 16 or older → rejected. */
  @Test
  void sixteenOrOlderIsNotADependent() throws Exception {
    var res = create(child("Van Lon", yearsAgo(16, 0), true));
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(res.body()).contains("not-a-dependent");
    assertThat(create(child("Van Nho", yearsAgo(16, 1), true)).statusCode())
        .isEqualTo(201); // one day short
  }

  /** AC-DP.3: an 11th profile is rejected. */
  @Test
  void anEleventhProfileIsRejected() throws Exception {
    for (int i = 0; i < 10; i++) {
      mongo.insert(
          new Document("_id", "l" + i + account)
              .append("accountSub", account)
              .append("patientId", "p" + i)
              .append("relationship", "proxy")
              .append("scope", "booking")
              .append("status", "active")
              .append("expiresAt", null)
              .append("createdAt", new Date()),
          "profile_links");
    }
    var res = create(child("Van Muoi Mot", yearsAgo(5, 0), true));
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(res.body()).contains("profile-limit");
  }

  @Test
  void declarationDuplicateAndRolesAreChecked() throws Exception {
    assertThat(create(child("Van Nam", yearsAgo(8, 0), null)).statusCode()).isEqualTo(400);
    assertThat(create(child("Van Nam", yearsAgo(8, 0), false)).statusCode()).isEqualTo(400);
    String birth = yearsAgo(8, 0);
    assertThat(create(child("Van Nam", birth, true)).statusCode()).isEqualTo(201);
    var dup = create(child("Van Nam", birth, true));
    assertThat(dup.statusCode()).isEqualTo(422);
    assertThat(dup.body()).contains("duplicate-patient-suspected");
    assertThat(call("GET", "/api/v1/me/profiles", token(nurse, "nurse"), null).statusCode())
        .isEqualTo(403);
    assertThat(links()).isEqualTo(1);
  }

  @Test
  void aLinkNotActiveYetShowsAShortenedName() throws Exception {
    String relative = newPatient("Nguyen", "Van An", "+84901000052", "1960-01-01");
    mongo.insert(
        new Document("_id", "pending-" + account)
            .append("accountSub", account)
            .append("patientId", relative)
            .append("relationship", "proxy")
            .append("scope", "records")
            .append("status", "pending")
            .append("expiresAt", null)
            .append("createdAt", new Date()),
        "profile_links");
    JsonNode list =
        JSON.readTree(call("GET", "/api/v1/me/profiles", token(account, "patient"), null).body());
    assertThat(list.get(1).get("displayName").asString()).isEqualTo("N. V. An");
  }
}
