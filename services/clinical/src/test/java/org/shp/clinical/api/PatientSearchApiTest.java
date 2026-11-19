package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.ClinicalApiTestBase;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.fhir.FhirJson;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CLN-08 / C6 (US-031; AC-06.1, AC-06.2) on real HTTP, real authorization and a real MongoDB. Fake
 * data only.
 */
class PatientSearchApiTest extends ClinicalApiTestBase {
  @Autowired PatientRepository patients;

  String an;
  String anMrn;
  String nurse;
  String doctor;
  String patient;

  @BeforeEach
  void fixtures() {
    Entry e = newPatient("Nguyễn", "Văn An", "+84901000021", "1985-03-14");
    an = e.id();
    anMrn = e.search().getString("mrn");
    newPatient("Nguyễn", "Thị Vân", "+84901000022", "1990-07-21");
    newPatient("Trần", "Văn Bình", "+84901000023", "1975-11-02");
    long n = System.nanoTime();
    nurse = "nurse-c6-" + n;
    doctor = "doc-c6-" + n;
    patient = "pat-c6-" + n;
    link(nurse, "Practitioner/prac-nurse-c6");
    link(doctor, "Practitioner/prac-doc-c6");
    link(patient, "Patient/" + an);
  }

  Entry newPatient(String family, String given, String phone, String birthDate) {
    Patient p = new Patient();
    p.addName()
        .setUse(NameUse.OFFICIAL)
        .setText(family + " " + given)
        .setFamily(family)
        .addGiven(given);
    p.addTelecom().setSystem(ContactPointSystem.PHONE).setValue(phone);
    p.addTelecom()
        .setSystem(ContactPointSystem.EMAIL)
        .setValue("p" + System.nanoTime() + "@example.test");
    p.setGender(AdministrativeGender.MALE);
    p.setBirthDateElement(new DateType(birthDate));
    return patients.create(p, "User/test", null);
  }

  HttpResponse<String> search(String token, String... form) throws Exception {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i + 1 < form.length; i += 2) {
      if (!body.isEmpty()) {
        body.append('&');
      }
      body.append(form[i])
          .append('=')
          .append(URLEncoder.encode(form[i + 1], StandardCharsets.UTF_8));
    }
    return call(
        "POST",
        "/fhir/Patient/_search",
        token,
        body.toString(),
        "Content-Type",
        "application/x-www-form-urlencoded");
  }

  static List<Patient> results(HttpResponse<String> res) {
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    Bundle b = FhirJson.parse(res.body(), Bundle.class);
    return b.getEntry().stream().map(e -> (Patient) e.getResource()).toList();
  }

  /** AC-06.1: "nguyen va" (no diacritics, last token by prefix) finds the right people. */
  @Test
  void nameWithoutDiacriticsMatchesTheLastTokenByPrefix() throws Exception {
    List<Patient> found = results(search(token(nurse, "nurse"), "name", "nguyen va"));
    assertThat(found)
        .extracting(p -> p.getNameFirstRep().getText())
        .containsExactlyInAnyOrder("Nguyễn Văn An", "Nguyễn Thị Vân");
    assertThat(results(search(token(nurse, "nurse"), "name", "nguyen van a")))
        .extracting(p -> p.getIdElement().getIdPart())
        .containsExactly(an);
  }

  @Test
  void phoneIsMatchedAfterNormalizationAndMrnByIdentifier() throws Exception {
    assertThat(results(search(token(nurse, "nurse"), "phone", "0901000021")))
        .extracting(p -> p.getIdElement().getIdPart())
        .containsExactly(an);
    assertThat(results(search(token(nurse, "nurse"), "identifier", "urn:shp:mrn|" + anMrn)))
        .extracting(p -> p.getIdElement().getIdPart())
        .containsExactly(an);
    assertThat(results(search(token(nurse, "nurse"), "birthdate", "1985-03-14"))).hasSize(1);
    assertThat(results(search(token(nurse, "nurse"), "phone", "not-a-phone"))).isEmpty();
  }

  @Test
  void resultsAreShortenedAndTheBundleCarriesNoSearchValues() throws Exception {
    var res = search(token(nurse, "nurse"), "phone", "0901000021");
    Patient p = results(res).getFirst();
    assertThat(p.getBirthDateElement().getValueAsString()).isEqualTo("1985-03-14");
    assertThat(p.getIdentifierFirstRep().getValue()).isEqualTo(anMrn);
    assertThat(p.hasTelecom()).isFalse();
    assertThat(p.hasAddress()).isFalse();
    assertThat(res.body())
        .doesNotContain("0901000021")
        .doesNotContain("+84901000021")
        .doesNotContain("example.test");
  }

  /** AC-06.2: a doctor without care sees only the birth year; with care the full date. */
  @Test
  void aDoctorWithoutCareSeesOnlyTheBirthYear() throws Exception {
    Patient p = results(search(token(doctor, "doctor"), "name", "nguyen van a")).getFirst();
    assertThat(p.getBirthDateElement().getValueAsString()).isEqualTo("1985");

    mongo.save(
        new Document("_id", "prac-doc-c6|" + an)
            .append("practitionerId", "prac-doc-c6")
            .append("patientId", an)
            .append("sources", List.of())
            .append("validUntil", Date.from(Instant.now().plusSeconds(3600))),
        "care_relationships");
    p = results(search(token(doctor, "doctor"), "name", "nguyen van a")).getFirst();
    assertThat(p.getBirthDateElement().getValueAsString()).isEqualTo("1985-03-14");
  }

  @Test
  void parameterRules() throws Exception {
    assertThat(search(token(nurse, "nurse")).statusCode()).isEqualTo(400); // no parameter
    assertThat(search(token(nurse, "nurse"), "name", "n").statusCode()).isEqualTo(400);
    assertThat(search(token(nurse, "nurse"), "address", "Hanoi").statusCode()).isEqualTo(400);
    assertThat(search(token(nurse, "nurse"), "name", "nguyen", "_page_token", "x").statusCode())
        .isEqualTo(400);
  }

  @Test
  void patientsCannotSearchAndEverySearchIsAuditedWithoutValues() throws Exception {
    assertThat(search(token(patient, "patient"), "name", "nguyen").statusCode()).isEqualTo(403);
    search(token(nurse, "nurse"), "name", "nguyen va");
    // the search audit record is written after the response: wait for it
    List<Document> searches = List.of();
    for (int i = 0; i < 50 && searches.isEmpty(); i++) {
      searches =
          auditRecords().stream().filter(a -> "search".equals(a.getString("action"))).toList();
      if (searches.isEmpty()) {
        Thread.sleep(100);
      }
    }
    assertThat(searches).isNotEmpty();
    assertThat(searches.toString()).doesNotContain("nguyen").doesNotContain("Nguyễn");
  }
}
