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
import org.hl7.fhir.r4.model.Patient.LinkType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.ClinicalApiTestBase;
import org.shp.clinical.infra.PatientRepository;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;
import org.shp.fhir.FhirJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * CLN-09 / C8 (US-004; 13 §3 E7) on real HTTP, real authorization and a real MongoDB: merge a
 * duplicate record into the one that stays. Fake data only.
 */
class PatientMergeApiTest extends ClinicalApiTestBase {
  @Autowired PatientRepository patients;

  String source;
  String target;
  String nurse;
  String doctor;
  String owner;

  @BeforeEach
  void fixtures() {
    source = newPatient("Le", "Van Mot", "+84901000031", "1980-01-01");
    target = newPatient("Le", "Van Mot", "+84901000032", "1980-01-01");
    long n = System.nanoTime();
    nurse = "nurse-c8-" + n;
    doctor = "doc-c8-" + n;
    owner = "pat-c8-" + n;
    link(nurse, "Practitioner/prac-nurse-c8");
    link(doctor, "Practitioner/prac-doc-c8");
    link(owner, "Patient/" + source);
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
    return patients.create(p, "User/test", null).id();
  }

  HttpResponse<String> merge(String token, String id, String targetId) throws Exception {
    return call(
        "POST",
        "/fhir/Patient/" + id + "/$merge",
        token,
        "{\"target\":{\"reference\":\"Patient/" + targetId + "\"}}");
  }

  List<Document> mergedEvents() {
    return mongo.find(
        Query.query(Criteria.where("eventType").is("shp.clinical.patient-merged.v1")),
        Document.class,
        "outbox");
  }

  @Test
  void aNurseMergesTheSourceIntoTheTarget() throws Exception {
    var res = merge(token(nurse, "nurse"), source, target);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    Patient s = FhirJson.parse(res.body(), Patient.class);
    assertThat(s.getActive()).isFalse();
    assertThat(s.getLink())
        .singleElement()
        .satisfies(l -> {
          assertThat(l.getType()).isEqualTo(LinkType.REPLACEDBY);
          assertThat(l.getOther().getReference()).isEqualTo("Patient/" + target);
        });
    assertThat(res.headers().firstValue("ETag")).contains("W/\"2\"");
    // the target is untouched; both keep their history
    assertThat(PatientRepository.toFhir(patients.find(target).orElseThrow()).getActive()).isTrue();
    assertThat(patients.history(source)).hasSize(2);
  }

  @Test
  void theMergePublishesE7InTheSameTransaction() throws Exception {
    merge(token(nurse, "nurse"), source, target);
    List<Document> events = mergedEvents();
    assertThat(events).hasSize(1);
    Document payload = events.get(0).get("payload", Document.class);
    var evt = CloudEvents.parse(payload.toJson());
    assertThat(EventSchemas.validate(evt)).isEmpty();
    assertThat(evt.data().get("sourcePatientId").asString()).isEqualTo(source);
    assertThat(evt.data().get("targetPatientId").asString()).isEqualTo(target);
    assertThat(payload.toJson()).doesNotContain("Van Mot").doesNotContain("+84901000031");
  }

  /** Done-when: merging again → 409. */
  @Test
  void mergingAnAlreadyMergedRecordIs409() throws Exception {
    assertThat(merge(token(nurse, "nurse"), source, target).statusCode()).isEqualTo(200);
    assertThat(merge(token(nurse, "nurse"), source, target).statusCode()).isEqualTo(409);
    String third = newPatient("Pham", "Thi Ba", "+84901000033", "1990-02-02");
    assertThat(merge(token(nurse, "nurse"), third, source).statusCode())
        .isEqualTo(409); // into a merged one
    assertThat(mergedEvents()).hasSize(1);
  }

  @Test
  void badTargets() throws Exception {
    assertThat(merge(token(nurse, "nurse"), source, source).statusCode()).isEqualTo(400);
    assertThat(
            merge(token(nurse, "nurse"), source, "0192f8a4-6c1e-7b2a-9f00-0000000000aa")
                .statusCode())
        .isEqualTo(404);
    assertThat(
            call("POST", "/fhir/Patient/" + source + "/$merge", token(nurse, "nurse"), "{}")
                .statusCode())
        .isEqualTo(400);
    assertThat(mergedEvents()).isEmpty();
  }

  @Test
  void onlyNursesMerge() throws Exception {
    assertThat(merge(token(doctor, "doctor"), source, target).statusCode()).isIn(403, 404);
    assertThat(merge(token(owner, "patient"), source, target).statusCode()).isEqualTo(403);
    assertThat(merge(null, source, target).statusCode()).isEqualTo(401);
    assertThat(PatientRepository.toFhir(patients.find(source).orElseThrow()).getActive()).isTrue();
  }
}
