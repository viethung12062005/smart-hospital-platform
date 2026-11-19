package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import org.bson.Document;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.ClinicalApiTestBase;
import org.shp.fhir.FhirJson;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * CLN-07 / C3 (US-003) on real HTTP, real authorization and a real MongoDB: a nurse creates a
 * record at the counter; a suspected duplicate is 422 unless overridden with a reason, and the
 * override is audited. Fake data only.
 */
class CounterRegistrationApiTest extends ClinicalApiTestBase {
  static final String FHIR = "application/fhir+json";
  static final String PHONE = "+84901000011";
  static final String BIRTH = "1988-04-02";

  String nurse;
  String doctor;
  String patient;

  @BeforeEach
  void accounts() {
    long n = System.nanoTime();
    nurse = "nurse-c3-" + n;
    doctor = "doc-c3-" + n;
    patient = "pat-c3-" + n;
    link(nurse, "Practitioner/prac-nurse-c3");
    link(doctor, "Practitioner/prac-doc-c3");
    link(patient, "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e99");
  }

  static String body(String family, String given, String phone, String birthDate) {
    Patient p = new Patient();
    p.addName()
        .setUse(NameUse.OFFICIAL)
        .setText(family + " " + given)
        .setFamily(family)
        .addGiven(given);
    p.addTelecom().setSystem(ContactPointSystem.PHONE).setValue(phone);
    p.setGender(AdministrativeGender.FEMALE);
    p.setBirthDateElement(new DateType(birthDate));
    return FhirJson.serialize(p);
  }

  HttpResponse<String> create(String token, String body, String query, String... headers)
      throws Exception {
    String[] all = new String[headers.length + 2];
    all[0] = "Content-Type";
    all[1] = FHIR;
    System.arraycopy(headers, 0, all, 2, headers.length);
    return call("POST", "/fhir/Patient" + query, token, body, all);
  }

  long patients() {
    return mongo.count(new Query(), "patients");
  }

  @Test
  void aNurseCreatesARecordWithAnMrn() throws Exception {
    var res = create(token(nurse, "nurse"), body("Le", "Thi Hoa", PHONE, BIRTH), "");
    assertThat(res.statusCode()).isEqualTo(201);
    Patient p = FhirJson.parse(res.body(), Patient.class);
    assertThat(p.getIdentifier())
        .anySatisfy(i -> {
          assertThat(i.getSystem()).isEqualTo("urn:shp:mrn");
          assertThat(i.getValue()).matches("BN-\\d{4}-\\d{6}");
        });
    assertThat(p.getActive()).isTrue();
    assertThat(res.headers().firstValue("Location")).get().asString().startsWith("/fhir/Patient/");
    assertThat(res.headers().firstValue("ETag")).isPresent();
  }

  @Test
  void serverOwnedElementsAreReplaced() throws Exception {
    Patient sent = FhirJson.parse(body("Le", "Thi Hoa", PHONE, BIRTH), Patient.class);
    sent.setId("client-id");
    sent.setActive(false);
    sent.addIdentifier().setSystem("urn:shp:mrn").setValue("BN-1999-000001");
    var res = create(token(nurse, "nurse"), FhirJson.serialize(sent), "");
    assertThat(res.statusCode()).isEqualTo(201);
    Patient p = FhirJson.parse(res.body(), Patient.class);
    assertThat(p.getIdElement().getIdPart()).isNotEqualTo("client-id");
    assertThat(p.getActive()).isTrue();
    assertThat(p.getIdentifier()).noneMatch(i -> "BN-1999-000001".equals(i.getValue()));
  }

  /** Done-when: no forceCreate when a duplicate is suspected → 422. */
  @Test
  void aSuspectedDuplicateIs422AndNothingIsCreated() throws Exception {
    assertThat(create(token(nurse, "nurse"), body("Le", "Thi Hoa", PHONE, BIRTH), "").statusCode())
        .isEqualTo(201);
    var res =
        create(
            token(nurse, "nurse"), body("Le", "Thi Hoa B", PHONE, BIRTH), ""); // same phone + DOB
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(res.body()).contains("duplicate-patient-suspected").doesNotContain("BN-");
    assertThat(patients()).isEqualTo(1);
  }

  @Test
  void forceCreateNeedsAReason() throws Exception {
    create(token(nurse, "nurse"), body("Le", "Thi Hoa", PHONE, BIRTH), "");
    var noReason =
        create(token(nurse, "nurse"), body("Le", "Thi Hoa", PHONE, BIRTH), "?forceCreate=true");
    assertThat(noReason.statusCode()).isEqualTo(400);
    var shortReason =
        create(
            token(nurse, "nurse"),
            body("Le", "Thi Hoa", PHONE, BIRTH),
            "?forceCreate=true",
            CounterRegistrationController.OVERRIDE_HEADER,
            "twins");
    assertThat(shortReason.statusCode()).isEqualTo(400);
    assertThat(patients()).isEqualTo(1);
  }

  @Test
  void anOverrideCreatesTheRecordAndIsAuditedWithItsReason() throws Exception {
    create(token(nurse, "nurse"), body("Le", "Thi Hoa", PHONE, BIRTH), "");
    String reason = "Twins checked with ID cards at the counter (test)";
    var res =
        create(
            token(nurse, "nurse"),
            body("Le", "Thi Lan", PHONE, BIRTH),
            "?forceCreate=true",
            CounterRegistrationController.OVERRIDE_HEADER,
            reason);
    assertThat(res.statusCode()).isEqualTo(201);
    assertThat(patients()).isEqualTo(2);
    String id = FhirJson.parse(res.body(), Patient.class).getIdElement().getIdPart();
    List<Document> overrides =
        auditRecords().stream()
            .filter(a -> ("duplicate-override: " + reason).equals(a.getString("reason")))
            .toList();
    assertThat(overrides)
        .singleElement()
        .satisfies(a -> {
          assertThat(a.getString("action")).isEqualTo("create");
          assertThat(a.getString("resource")).isEqualTo("Patient/" + id);
          assertThat(a.get("actor", Document.class).getString("role")).isEqualTo("nurse");
        });
  }

  @Test
  void onlyNursesCreate() throws Exception {
    assertThat(
            create(token(doctor, "doctor"), body("Le", "Thi Hoa", PHONE, BIRTH), "").statusCode())
        .isEqualTo(403);
    assertThat(
            create(token(patient, "patient"), body("Le", "Thi Hoa", PHONE, BIRTH), "").statusCode())
        .isEqualTo(403);
    assertThat(create(null, body("Le", "Thi Hoa", PHONE, BIRTH), "").statusCode()).isEqualTo(401);
    assertThat(patients()).isZero();
  }

  @Test
  void anOutOfProfileBodyIs400() throws Exception {
    Patient sent = FhirJson.parse(body("Le", "Thi Hoa", PHONE, BIRTH), Patient.class);
    sent.setBirthDateElement(new DateType("2999-01-01"));
    assertThat(create(token(nurse, "nurse"), FhirJson.serialize(sent), "").statusCode())
        .isEqualTo(400);
    assertThat(mongo.exists(Query.query(Criteria.where("_id").exists(true)), "patients")).isFalse();
  }
}
