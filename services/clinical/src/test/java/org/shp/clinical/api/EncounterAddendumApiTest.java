package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.ClinicalApiTestBase;
import org.shp.clinical.app.Encounters;
import org.shp.fhir.FhirJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** C15: real HTTP, authorization, audit and MongoDB. */
class EncounterAddendumApiTest extends ClinicalApiTestBase {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @Autowired Encounters encounters;
  String encounter;
  String patient;
  String doctor;
  String doctorSubject;
  String nurseSubject;
  String otherSubject;

  @BeforeEach
  void fixture() {
    mongo.remove(new Query(), "encounters");
    encounter = UUID.randomUUID().toString();
    patient = UUID.randomUUID().toString();
    doctor = UUID.randomUUID().toString();
    doctorSubject = "doctor-" + encounter;
    nurseSubject = "nurse-" + encounter;
    otherSubject = "other-" + encounter;
    link(doctorSubject, "Practitioner/" + doctor);
    link(nurseSubject, "Practitioner/" + UUID.randomUUID());
    link(otherSubject, "Practitioner/" + UUID.randomUUID());
    mongo.save(
        new Document("_id", doctor + "|" + patient)
            .append("validUntil", Date.from(Instant.now().plus(30, ChronoUnit.DAYS))),
        "care_relationships");
  }

  void seed(String status) {
    Document resource =
        Document.parse(
            """
            {"resourceType":"Encounter","id":"%s","status":"%s",
             "class":{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode","code":"AMB"},
             "subject":{"reference":"Patient/%s"},"reasonCode":[{"text":"Original test note"}]}
            """
                .formatted(encounter, status, patient));
    encounters.create(
        encounter,
        patient,
        resource,
        new Document("status", status).append("practitionerId", doctor),
        "test");
  }

  java.net.http.HttpResponse<String> add(String subject, String role, String version)
      throws Exception {
    return version == null
        ? call(
            "POST",
            "/fhir/Encounter/" + encounter + "/$addendum",
            token(subject, role),
            "{\"text\":\"Test addendum\"}")
        : call(
            "POST",
            "/fhir/Encounter/" + encounter + "/$addendum",
            token(subject, role),
            "{\"text\":\"Test addendum\"}",
            "If-Match",
            version);
  }

  @Test
  void finishedEncounterKeepsOriginalAndRecordsAuthorTimeAndAllowedAudit() throws Exception {
    seed("finished");
    var before =
        (ObjectNode)
            JSON.readTree(
                FhirJson.serialize(
                    FhirJson.parse(
                        encounters.find(encounter).orElseThrow().resource().toJson(),
                        Encounter.class)));
    var response = add(doctorSubject, "doctor", "W/\"1\"");
    assertThat(response.statusCode()).isEqualTo(200);
    var result = JSON.readTree(response.body());
    assertThat(result.get("status").asString()).isEqualTo("finished");
    assertThat(result.get("reasonCode").get(0).get("text").asString())
        .isEqualTo("Original test note");
    var original = ((ObjectNode) result).deepCopy();
    original.remove(List.of("meta", "extension"));
    before.remove(List.of("meta", "extension"));
    assertThat(original).isEqualTo(before);
    var extension = result.get("extension").get(0).get("extension");
    assertThat(extension.toString())
        .contains("Practitioner/" + doctor, "recordedAt", "Test addendum");
    assertThat(encounters.find(encounter).orElseThrow().version()).isEqualTo(2);
    await().untilAsserted(() -> assertAudit(doctorSubject, "success"));
  }

  @Test
  void nurseAndDoctorWithoutCareArePolicyDenialsWithAudit() throws Exception {
    seed("finished");
    assertThat(add(nurseSubject, "nurse", "W/\"1\"").statusCode()).isEqualTo(403);
    assertThat(add(otherSubject, "doctor", "W/\"1\"").statusCode()).isEqualTo(403);
    await()
        .untilAsserted(() -> {
          assertAudit(nurseSubject, "denied");
          assertAudit(otherSubject, "denied");
        });
    assertThat(encounters.find(encounter).orElseThrow().version()).isEqualTo(1);
  }

  @Test
  void onlyFinishedEncountersAndMatchingVersionsCanBeUpdated() throws Exception {
    seed("in-progress");
    assertThat(add(nurseSubject, "nurse", "W/\"1\"").statusCode()).isEqualTo(403);
    await().untilAsserted(() -> assertAudit(nurseSubject, "denied"));
    assertThat(add(doctorSubject, "doctor", "W/\"1\"").statusCode()).isEqualTo(409);
    mongo.remove(new Query(), "encounters");
    mongo.remove(new Query(), "resource_history");
    seed("finished");
    assertThat(add(doctorSubject, "doctor", null).statusCode()).isEqualTo(428);
    assertThat(add(doctorSubject, "doctor", "W/\"2\"").statusCode()).isEqualTo(412);
    assertThat(encounters.find(encounter).orElseThrow().version()).isEqualTo(1);
  }

  void assertAudit(String subject, String outcome) {
    assertThat(auditRecords())
        .anySatisfy(a -> {
          assertThat(a.get("actor", Document.class).getString("subject")).isEqualTo(subject);
          assertThat(a.getString("action")).isEqualTo("update");
          assertThat(a.getString("outcome")).isEqualTo(outcome);
          assertThat(a.getString("resource")).isEqualTo("Encounter/" + encounter);
        });
  }
}
