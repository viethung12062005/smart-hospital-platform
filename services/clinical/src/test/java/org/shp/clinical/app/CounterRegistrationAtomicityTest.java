package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoOutbox;
import org.shp.events.CloudEvent;
import org.shp.fhir.FhirJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/** Regression for #715: a failed override audit must roll back the new Patient. */
@SpringBootTest(
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
@Import(CounterRegistrationAtomicityTest.FailingAuditOutbox.class)
class CounterRegistrationAtomicityTest {
  @Container
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();

  @DynamicPropertySource
  static void mongo(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", () -> DB.getReplicaSetUrl("clinical"));
  }

  @Autowired CounterRegistrationService service;
  @Autowired PatientRepository patients;
  @Autowired MongoTemplate mongo;

  @BeforeEach
  void clean() {
    mongo.remove(new Query(), "patients");
    mongo.remove(new Query(), "mrn_counters");
    mongo.remove(new Query(), "resource_history");
    mongo.remove(new Query(), "outbox");
  }

  @Test
  void failedDuplicateOverrideAuditRollsBackThePatientAndHistory() {
    patients.create(patient("Original"), "User/nurse-c3", null);

    assertThatThrownBy(() ->
            service.create(
                FhirJson.serialize(patient("Override")),
                true,
                "Twin verified with a synthetic identity card",
                new CounterRegistrationService.Nurse("nurse-c3", "prac-c3")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("simulated audit outbox failure");

    assertThat(mongo.count(new Query(), "patients")).isEqualTo(1);
    assertThat(mongo.count(new Query(), "resource_history")).isEqualTo(1);
    assertThat(mongo.count(new Query(), "outbox")).isZero();
  }

  private static Patient patient(String given) {
    Patient patient = new Patient();
    patient
        .addName()
        .setUse(NameUse.OFFICIAL)
        .setText("Synthetic " + given)
        .setFamily("Synthetic")
        .addGiven(given);
    patient.addTelecom().setSystem(ContactPointSystem.PHONE).setValue("+84901000011");
    patient.setGender(AdministrativeGender.FEMALE);
    patient.setBirthDateElement(new DateType("1988-04-02"));
    return patient;
  }

  @TestConfiguration
  static class FailingAuditOutbox {
    @Bean
    @Primary
    MongoOutbox failingAuditOutbox(MongoTemplate mongo) {
      MongoOutbox outbox = spy(new MongoOutbox(mongo));
      doAnswer(
              invocation -> {
                CloudEvent event = invocation.getArgument(0);
                if ("shp.audit.recorded.v1".equals(event.type())) {
                  throw new IllegalStateException("simulated audit outbox failure");
                }
                return invocation.callRealMethod();
              })
          .when(outbox)
          .add(any(CloudEvent.class));
      return outbox;
    }
  }
}
