package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import org.bson.Document;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.ClinicalApiTestBase;
import org.shp.clinical.infra.PatientRepository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CLN-24 / C20 on real HTTP, real authorization and a real MongoDB: the {@code break-glass-start}
 * audit record carries the reason (up to 500 characters) and the real outcome. Fake data only.
 */
class BreakGlassApiTest extends ClinicalApiTestBase {
  static final String PATH = "/api/v1/clinical/break-glass";
  static final String REASON =
      "Patient unconscious in the department, no family member present, emergency access (test)";

  @Autowired PatientRepository patients;

  String patientId;
  String doctor;

  @BeforeEach
  void fixtures() {
    Patient p = new Patient();
    p.addName()
        .setUse(NameUse.OFFICIAL)
        .setText("Nguyen Van An")
        .setFamily("Nguyen")
        .addGiven("Van An");
    p.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setValue("+84901000001");
    p.setGender(AdministrativeGender.MALE);
    p.setBirthDateElement(new org.hl7.fhir.r4.model.DateType("1985-03-14"));
    patientId = patients.create(p, "seed", null).id();
    // the identity resolver caches subject -> link for 60 s, so every test uses fresh subjects
    doctor = "bg-doc-" + patientId;
    link(doctor, "Practitioner/prac-bg-" + patientId);
  }

  String body(String reason) {
    return "{\"patientId\":\"" + patientId + "\",\"reason\":\"" + reason + "\"}";
  }

  List<Document> startRecords(String outcome) {
    return auditRecords().stream()
        .filter(a -> "break-glass-start".equals(a.getString("action")))
        .filter(a -> outcome.equals(a.getString("outcome")))
        .toList();
  }

  @Test
  void aStartedSessionIsAuditedWithTheReasonAndEtreat() throws Exception {
    var r = call("POST", PATH, token(doctor, "doctor"), body(REASON));
    assertThat(r.statusCode()).isEqualTo(201);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(startRecords("success")).hasSize(1));
    Document rec = startRecords("success").get(0);
    assertThat(rec.getString("reason")).isEqualTo(REASON);
    assertThat(rec.getString("purposeOfUse")).isEqualTo("ETREAT");
  }

  @Test
  void aFiveHundredCharacterReasonIsAcceptedAndRecordedWhole() throws Exception {
    String reason = "r".repeat(500);
    var r = call("POST", PATH, token(doctor, "doctor"), body(reason));
    assertThat(r.statusCode()).isEqualTo(201);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(startRecords("success")).hasSize(1));
    assertThat(startRecords("success").get(0).getString("reason")).isEqualTo(reason);
  }

  @Test
  void aSecondStartIs409AndAuditedAsAnError() throws Exception {
    assertThat(call("POST", PATH, token(doctor, "doctor"), body(REASON)).statusCode())
        .isEqualTo(201);
    var again = call("POST", PATH, token(doctor, "doctor"), body(REASON));
    assertThat(again.statusCode()).isEqualTo(409);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          assertThat(startRecords("success")).hasSize(1);
          assertThat(startRecords("error")).hasSize(1);
        });
    assertThat(startRecords("error").get(0).getString("reason")).isEqualTo(REASON);
  }

  @Test
  void aTooShortReasonIs400AndAuditedAsAnErrorWithoutASession() throws Exception {
    var r = call("POST", PATH, token(doctor, "doctor"), body("too short"));
    assertThat(r.statusCode()).isEqualTo(400);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(startRecords("error")).hasSize(1));
    assertThat(startRecords("success")).isEmpty();
  }

  @Test
  void anUnknownPatientIs404AndAuditedAsAnError() throws Exception {
    String unknown = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5eff";
    var r =
        call(
            "POST",
            PATH,
            token(doctor, "doctor"),
            "{\"patientId\":\"" + unknown + "\",\"reason\":\"" + REASON + "\"}");
    assertThat(r.statusCode()).isEqualTo(404);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(startRecords("error")).hasSize(1));
  }

  @Test
  void rolesWithoutARuleAreRefusedWith403WhateverTheBody() throws Exception {
    for (String role : new String[] {"patient", "nurse", "admin", "dpo"}) {
      String sub = "bg-" + role + "-" + patientId;
      link(sub, "patient".equals(role) ? "Patient/" + patientId : "Practitioner/prac-" + sub);
      for (String bad : new String[] {"{}", "[]", "not json", body(REASON)}) {
        var r = call("POST", PATH, token(sub, role), bad);
        assertThat(r.statusCode()).as(role + " " + bad).isEqualTo(403);
      }
    }
  }

  @Test
  void anAuthorizedDoctorWithABadBodyGets400AuditedAsAnError() throws Exception {
    for (String bad : new String[] {"{}", "[]", "not json", "{\"patientId\":\"a b\"}"}) {
      assertThat(call("POST", PATH, token(doctor, "doctor"), bad).statusCode())
          .as(bad)
          .isEqualTo(400);
    }
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(startRecords("error")).hasSize(4));
    assertThat(startRecords("success")).isEmpty();
  }
}
