package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import org.bson.Document;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.ClinicalApiTestBase;
import org.shp.clinical.app.NationalIdRules;
import org.shp.clinical.infra.PatientRepository;
import org.shp.fhir.FhirJson;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CLN-30 / C4, C5 (US-075; AC-ID.1–AC-ID.3) on real HTTP, real authorization and a real MongoDB:
 * the CCCD is written by a nurse with consent, stored encrypted, returned masked, revealed only on
 * request with an audit record. Fake numbers only (province 001).
 */
class NationalIdApiTest extends ClinicalApiTestBase {
  /** Male born 1985: digit 4 = 0 (male, 1900s), digits 5-6 = 85. */
  static final String NUMBER = "001085012345";

  @Autowired PatientRepository patients;

  String an;
  String binh;
  String nurse;
  String owner;
  String doctor;

  @BeforeEach
  void fixtures() {
    an = newPatient("Le", "Van An", "+84901000041", "1985-03-14", AdministrativeGender.MALE);
    binh =
        newPatient("Tran", "Thi Binh", "+84901000042", "1990-07-21", AdministrativeGender.FEMALE);
    long n = System.nanoTime();
    nurse = "nurse-id-" + n;
    owner = "pat-id-" + n;
    doctor = "doc-id-" + n;
    link(nurse, "Practitioner/prac-nurse-id");
    link(owner, "Patient/" + an);
    link(doctor, "Practitioner/prac-doc-id");
  }

  String newPatient(
      String family, String given, String phone, String birth, AdministrativeGender g) {
    Patient p = new Patient();
    p.addName()
        .setUse(NameUse.OFFICIAL)
        .setText(family + " " + given)
        .setFamily(family)
        .addGiven(given);
    p.addTelecom().setSystem(ContactPointSystem.PHONE).setValue(phone);
    p.setGender(g);
    p.setBirthDateElement(new DateType(birth));
    return patients.create(p, "User/test", null).id();
  }

  HttpResponse<String> get(String token, String id, String query) throws Exception {
    return call("GET", "/fhir/Patient/" + id + query, token, null);
  }

  /** PUT the current record with {@code cccd} added (null: unchanged echo of the response). */
  HttpResponse<String> putWith(String token, String id, Identifier cccd) throws Exception {
    var current = get(token(nurse, "nurse"), id, "");
    Patient p = FhirJson.parse(current.body(), Patient.class);
    String etag = current.headers().firstValue("ETag").orElseThrow();
    if (cccd != null) {
      p.getIdentifier().removeIf(i -> NationalIdRules.SYSTEM.equals(i.getSystem()));
      p.addIdentifier(cccd);
    }
    p.setMeta(null);
    return call(
        "PUT",
        "/fhir/Patient/" + id,
        token,
        FhirJson.serialize(p),
        "Content-Type",
        "application/fhir+json",
        "If-Match",
        etag);
  }

  static Identifier cccd(String number, Boolean consent, String reason) {
    Identifier i = new Identifier().setSystem(NationalIdRules.SYSTEM).setValue(number);
    if (consent != null) {
      i.addExtension(new Extension(NationalIdRules.CONSENT, new BooleanType(consent)));
    }
    if (reason != null) {
      i.addExtension(new Extension(NationalIdRules.REASON, new StringType(reason)));
    }
    return i;
  }

  static String shown(HttpResponse<String> res) {
    return FhirJson.parse(res.body(), Patient.class).getIdentifier().stream()
        .filter(i -> NationalIdRules.SYSTEM.equals(i.getSystem()))
        .map(Identifier::getValue)
        .findFirst()
        .orElse(null);
  }

  String stored(String id) {
    return mongo.findById(id, Document.class, "patients").toJson();
  }

  /** AC-ID.1: stored encrypted, shown masked (last 4 digits). */
  @Test
  void aNurseStoresTheNumberEncryptedAndItIsShownMasked() throws Exception {
    var res = putWith(token(nurse, "nurse"), an, cccd(NUMBER, true, null));
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(shown(res)).isEqualTo("********2345");
    assertThat(res.body()).doesNotContain(NUMBER);

    String doc = stored(an);
    assertThat(doc)
        .doesNotContain(NUMBER)
        .contains("nationalIdEnc")
        .contains("\"nationalIdLast4\": \"2345\"");
    assertThat(mongo.findAll(Document.class, "resource_history").toString()).doesNotContain(NUMBER);
    assertThat(mongo.findAll(Document.class, "outbox").toString()).doesNotContain(NUMBER);
    assertThat(shown(get(token(nurse, "nurse"), an, ""))).isEqualTo("********2345");
    assertThat(shown(get(token(owner, "patient"), an, ""))).isEqualTo("********2345");
  }

  @Test
  void onlyANurseRevealsTheNumberAndEveryRevealIsAudited() throws Exception {
    putWith(token(nurse, "nurse"), an, cccd(NUMBER, true, null));
    var res = get(token(nurse, "nurse"), an, "?reveal=national-id");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(shown(res)).isEqualTo(NUMBER);
    List<Document> reveals =
        auditRecords().stream()
            .filter(a -> "reveal: national-id".equals(a.getString("reason")))
            .toList();
    assertThat(reveals)
        .singleElement()
        .satisfies(a -> assertThat(a.getString("resource")).isEqualTo("Patient/" + an));
    assertThat(auditRecords().toString()).doesNotContain(NUMBER);

    assertThat(get(token(owner, "patient"), an, "?reveal=national-id").statusCode()).isEqualTo(403);
    assertThat(get(token(nurse, "nurse"), an, "?reveal=everything").statusCode()).isEqualTo(400);
  }

  /** AC-ID.3: a malformed number or no consent is rejected. */
  @Test
  void aMalformedNumberOrNoConsentIsRejected() throws Exception {
    assertThat(putWith(token(nurse, "nurse"), an, cccd("00108501234", true, null)).statusCode())
        .isEqualTo(400);
    assertThat(putWith(token(nurse, "nurse"), an, cccd(NUMBER, null, null)).statusCode())
        .isEqualTo(400);
    assertThat(putWith(token(nurse, "nurse"), an, cccd(NUMBER, false, null)).statusCode())
        .isEqualTo(400);
    assertThat(stored(an)).doesNotContain("nationalIdEnc");
  }

  @Test
  void aNumberThatDoesNotMatchGenderOrBirthYearNeedsAReason() throws Exception {
    String femaleCode = "001185012345"; // digit 4 = 1: female 1900s, but An is male
    assertThat(putWith(token(nurse, "nurse"), an, cccd(femaleCode, true, null)).statusCode())
        .isEqualTo(400);
    assertThat(putWith(token(nurse, "nurse"), an, cccd(femaleCode, true, "short")).statusCode())
        .isEqualTo(400);
    assertThat(
            putWith(
                    token(nurse, "nurse"),
                    an,
                    cccd(femaleCode, true, "Card checked at the counter (test)"))
                .statusCode())
        .isEqualTo(200);
  }

  /** AC-ID.2: a number already held by another record → 422, nothing saved. */
  @Test
  void aNumberHeldByAnotherRecordIs422() throws Exception {
    assertThat(putWith(token(nurse, "nurse"), an, cccd(NUMBER, true, null)).statusCode())
        .isEqualTo(200);
    var res = putWith(token(nurse, "nurse"), binh, cccd(NUMBER, true, "Twin card check (test)"));
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(res.body()).contains("duplicate-patient-suspected").doesNotContain(an);
    assertThat(stored(binh)).doesNotContain("nationalIdEnc");
  }

  @Test
  void onlyNursesWriteTheNumber() throws Exception {
    assertThat(putWith(token(owner, "patient"), an, cccd(NUMBER, true, null)).statusCode())
        .isEqualTo(403);
    assertThat(stored(an)).doesNotContain("nationalIdEnc");
  }

  @Test
  void laterEditsAndTheMaskedEchoKeepTheNumber() throws Exception {
    putWith(token(nurse, "nurse"), an, cccd(NUMBER, true, null));
    // the client sends back what it received (masked): unchanged
    var echo = putWith(token(nurse, "nurse"), an, null);
    assertThat(echo.statusCode()).as(echo.body()).isEqualTo(200);
    assertThat(shown(echo)).isEqualTo("********2345");
    assertThat(stored(an)).contains("nationalIdEnc");
  }
}
