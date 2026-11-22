package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.ClinicalApiTestBase;
import org.shp.clinical.app.OtpClient;
import org.shp.clinical.infra.LinkAttempts;
import org.shp.clinical.infra.PatientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.query.Query;

/**
 * CLN-36 / C27, C28 (US-078; AC-CL.1–AC-CL.4) on real HTTP and MongoDB, with a fake in place of
 * notification's OTP API (N8, N9). Fake data only.
 */
@Import(ClaimRecordApiTest.FakeOtpConfig.class)
class ClaimRecordApiTest extends ClinicalApiTestBase {
  static final String CODE = "246810";
  static final String PHONE = "+84901000061";

  /** N8/N9 stand-in: every code is {@link #CODE}; 5 wrong codes lock the challenge, like N9. */
  static class FakeOtp implements OtpClient {
    final List<String> sentTo = Collections.synchronizedList(new ArrayList<>());
    final Map<String, Integer> wrong = new HashMap<>();

    @Override
    public synchronized String send(String purpose, String destination, String locale) {
      assertThat(purpose).isEqualTo("claim-record");
      sentTo.add(destination);
      return "ch-" + sentTo.size();
    }

    @Override
    public synchronized Result verify(String challengeId, String code) {
      if (wrong.getOrDefault(challengeId, 0) >= 5) {
        return Result.LOCKED;
      }
      if (CODE.equals(code)) {
        return Result.OK;
      }
      wrong.merge(challengeId, 1, Integer::sum);
      return Result.INVALID;
    }

    synchronized void reset() {
      sentTo.clear();
      wrong.clear();
    }
  }

  @TestConfiguration
  static class FakeOtpConfig {
    @Bean
    @Primary
    FakeOtp fakeOtp() {
      return new FakeOtp();
    }
  }

  @Autowired PatientRepository patients;
  @Autowired FakeOtp otp;

  String an;
  String account;

  @BeforeEach
  void fixtures() {
    mongo.remove(new Query(), LinkAttempts.COLLECTION);
    otp.reset();
    an = newPatient("Nguyễn", "Văn An", PHONE, "1980-02-02");
    account = "acc-cl-" + System.nanoTime();
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

  static String body(String birthDate, String phone) {
    return """
    { "name": { "family": "Nguyen", "given": ["Van An"] }, "birthDate": "%s", "phone": "%s" }
    """
        .formatted(birthDate, phone);
  }

  HttpResponse<String> claim(String sub, String body) throws Exception {
    return call("POST", "/api/v1/me/claim-record", token(sub, "patient"), body);
  }

  HttpResponse<String> verify(String sub, String code) throws Exception {
    return call(
        "POST",
        "/api/v1/me/claim-record/verify",
        token(sub, "patient"),
        "{ \"otp\": \"" + code + "\" }");
  }

  Document linkOf(String sub) {
    return mongo.findById(sub, Document.class, "identity_links");
  }

  /** AC-CL.1: one match, right code → the account is linked, no new record. */
  @Test
  void theRightCodeLinksTheAccountToTheMatchingRecord() throws Exception {
    long before = mongo.count(new Query(), "patients");
    var res = claim(account, body("1980-02-02", "0901 000 061"));
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(res.body()).contains("claim-sent").doesNotContain(an);
    assertThat(otp.sentTo).containsExactly(PHONE); // the number in the record

    var ok = verify(account, CODE);
    assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
    assertThat(ok.body()).contains(an);
    assertThat(linkOf(account).getString("fhirUser")).isEqualTo("Patient/" + an);
    assertThat(mongo.count(new Query(), "patients")).isEqualTo(before);
    assertThat(auditRecords())
        .anySatisfy(
            a -> assertThat(a.getString("reason")).isEqualTo("claim-record: code verified"));
    assertThat(auditRecords().toString()).doesNotContain("1980-02-02").doesNotContain("901000061");
    assertThat(verify(account, CODE).statusCode()).isNotEqualTo(200); // a code links only once
  }

  /** AC-CL.2: no match or several candidates → same answer, no code, nothing revealed. */
  @Test
  void noMatchOrSeveralCandidatesAnswerTheSameAndSendNothing() throws Exception {
    var match = claim(account, body("1980-02-02", PHONE));
    otp.reset();
    var noMatch = claim("acc-other-" + System.nanoTime(), body("1981-02-02", PHONE));
    var wrongPhone = claim("acc-third-" + System.nanoTime(), body("1980-02-02", "0901000099"));
    assertThat(List.of(noMatch, wrongPhone))
        .allSatisfy(r -> {
          assertThat(r.statusCode()).isEqualTo(match.statusCode());
          assertThat(r.body()).isEqualTo(match.body());
        });

    newPatient("Nguyen", "Van An", PHONE, "1980-02-02"); // a second candidate
    String fourth = "acc-fourth-" + System.nanoTime();
    var several = claim(fourth, body("1980-02-02", PHONE));
    assertThat(several.body()).isEqualTo(match.body());
    assertThat(otp.sentTo).isEmpty();

    var res = verify(fourth, CODE); // no code was sent: even the "right" code fails
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(res.body()).contains("otp-invalid");
    assertThat(linkOf(fourth)).isNull();
  }

  /** AC-CL.3: 5 wrong codes lock; more than 5 claims in 24 hours are refused; both audited. */
  @Test
  void wrongCodesAndRepeatedClaimsAreLockedWithAnAudit() throws Exception {
    claim(account, body("1980-02-02", PHONE));
    for (int i = 0; i < 5; i++) {
      var res = verify(account, "000000");
      assertThat(res.statusCode()).isEqualTo(422);
    }
    var locked = verify(account, CODE);
    assertThat(locked.statusCode()).isEqualTo(429);
    assertThat(locked.body()).contains("otp-locked");
    assertThat(linkOf(account)).isNull();
    assertThat(auditRecords())
        .anySatisfy(a -> assertThat(a.getString("reason")).isEqualTo("claim-record: code locked"));

    String prober = "acc-probe-" + System.nanoTime();
    for (int i = 0; i < 5; i++) {
      assertThat(claim(prober, body("1990-01-0" + (i + 1), PHONE)).statusCode()).isEqualTo(200);
    }
    var sixth = claim(prober, body("1980-02-02", PHONE));
    assertThat(sixth.statusCode()).isEqualTo(429);
    assertThat(sixth.body()).contains("otp-rate-limited");
    assertThat(auditRecords())
        .anySatisfy(
            a -> assertThat(a.getString("reason")).isEqualTo("claim-record: limit per account"));
  }

  /** AC-CL.4: the record is already another account's "self" → no link, go to the counter. */
  @Test
  void aRecordLinkedToAnotherAccountIsNotLinkedAgain() throws Exception {
    link("acc-owner-" + System.nanoTime(), "Patient/" + an);
    claim(account, body("1980-02-02", PHONE));
    var res = verify(account, CODE);
    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(res.body()).contains("record-already-linked");
    assertThat(linkOf(account)).isNull();
  }

  @Test
  void aNumberGetsAtMostThreeCodesADay() throws Exception {
    for (int i = 0; i < 4; i++) {
      var res = claim("acc-n" + i + "-" + System.nanoTime(), body("1980-02-02", PHONE));
      assertThat(res.statusCode()).isEqualTo(200);
    }
    assertThat(otp.sentTo).hasSize(3);
  }

  @Test
  void onlyPatientAccountsWithoutARecordClaim() throws Exception {
    assertThat(
            call(
                    "POST",
                    "/api/v1/me/claim-record",
                    token("nurse-cl-" + System.nanoTime(), "nurse"),
                    body("1980-02-02", PHONE))
                .statusCode())
        .isEqualTo(403);
    String linked = "acc-linked-" + System.nanoTime();
    link(linked, "Patient/" + newPatient("Tran", "Thi Hoa", "+84901000062", "1991-01-01"));
    assertThat(claim(linked, body("1980-02-02", PHONE)).statusCode()).isEqualTo(409);
    assertThat(claim(account, body("not-a-date", PHONE)).statusCode()).isEqualTo(400);
    assertThat(otp.sentTo).isEmpty();
  }
}
