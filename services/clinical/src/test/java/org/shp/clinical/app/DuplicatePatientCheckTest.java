package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.Document;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * CLN-04 / AC-01.2: a record with the same phone number and date of birth is a suspected duplicate;
 * the refusal discloses nothing from the existing record. Real MongoDB 8, fake data only.
 */
@SpringBootTest(
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
class DuplicatePatientCheckTest {
  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1") // see MongoTestBase in shp-data-mongo
          .withReplicaSet();

  private static final String PHONE = "+84901234567";
  private static final String BIRTH = "1985-03-14";

  @Autowired DuplicatePatientCheck check;
  @Autowired PatientRepository patients;
  @Autowired MongoTemplate mongo;

  @BeforeEach
  void clean() {
    mongo.remove(new Query(), "patients");
    mongo.remove(new Query(), "resource_history");
  }

  private Entry existing(String phone, String birthDate) {
    Patient p = new Patient();
    p.addName()
        .setUse(NameUse.OFFICIAL)
        .setText("Hoang Thi Mai")
        .setFamily("Hoang")
        .addGiven("Thi Mai");
    p.addTelecom().setSystem(ContactPointSystem.PHONE).setValue(phone);
    p.setGender(AdministrativeGender.FEMALE);
    p.setBirthDateElement(new DateType(birthDate));
    return patients.create(p, "sub-1", null);
  }

  @Test
  void samePhoneAndBirthDateIsASuspectedDuplicate() {
    existing(PHONE, BIRTH);
    assertThat(check.isSuspected("0901234567", BIRTH)).isTrue();
    assertThatThrownBy(() -> check.requireNoDuplicate("0901234567", BIRTH))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("duplicate-patient-suspected");
              assertThat(e.info().status()).isEqualTo(422);
            });
  }

  @Test
  void anyCommonPhoneFormatMatchesTheStoredE164Number() {
    existing(PHONE, BIRTH);
    for (String format :
        new String[] {"0901234567", "+84901234567", "84901234567", "090 123 4567"}) {
      assertThat(check.isSuspected(format, BIRTH)).as(format).isTrue();
    }
  }

  @Test
  void aDifferentBirthDateOrPhoneIsNotADuplicate() {
    existing(PHONE, BIRTH);
    assertThat(check.isSuspected("0901234567", "1985-03-15")).isFalse();
    assertThat(check.isSuspected("0901234568", BIRTH)).isFalse();
    assertThatCode(() -> check.requireNoDuplicate("0901234568", BIRTH)).doesNotThrowAnyException();
  }

  @Test
  void aMergedRecordDoesNotCount() {
    Entry e = existing(PHONE, BIRTH);
    mongo.updateFirst(
        Query.query(Criteria.where("_id").is(e.id())),
        new Update().set("search.active", false),
        "patients");
    assertThat(check.isSuspected(PHONE, BIRTH)).isFalse();
  }

  @Test
  void anInvalidPhoneNeverMatches() {
    existing(PHONE, BIRTH);
    assertThat(check.isSuspected("not-a-phone", BIRTH)).isFalse();
    assertThat(check.isSuspected(null, BIRTH)).isFalse();
  }

  @Test
  void theRefusalDisclosesNothingFromTheExistingRecord() {
    Entry e = existing(PHONE, BIRTH);
    String mrn = e.search().getString("mrn");
    ShpException ex = null;
    try {
      check.requireNoDuplicate(PHONE, BIRTH);
    } catch (ShpException caught) {
      ex = caught;
    }
    assertThat(ex).isNotNull();
    String everything = ex.getMessage() + "|" + ex.detail() + "|" + ex.info();
    assertThat(everything)
        .doesNotContain(mrn)
        .doesNotContain(e.id())
        .doesNotContain("Hoang")
        .doesNotContain("Mai")
        .doesNotContain("901234567")
        .doesNotContain(BIRTH);
    assertThat(ex.getCause()).isNull();
  }

  @Test
  void theLookupUsesThePhoneAndBirthDateIndex() {
    existing(PHONE, BIRTH);
    Document plan =
        mongo
            .getDb()
            .getCollection("patients")
            .find(
                new Document("search.phone", PHONE)
                    .append("search.birthDate", BIRTH)
                    .append("search.active", true))
            .explain();
    assertThat(plan.toJson()).contains("IXSCAN").doesNotContain("COLLSCAN");
  }
}
