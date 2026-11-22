package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.bson.Document;
import org.hl7.fhir.r4.model.Encounter;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.events.EventSchemas;
import org.shp.web.errors.ShpException;

/** CLN-20 (US-028; 09 C14; 13 §3 E6): finishing a visit. Fake data only. */
class EncounterClosingTest {
  static final String ENC = EncounterServiceTest.ENC;
  static final String PAT = EncounterServiceTest.PAT;
  static final String DOC = EncounterServiceTest.DOC;
  static final Instant NOW = Instant.parse("2026-12-03T02:30:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  // E6 requires uuid ids: the CLN-14 intake stores the appointment id from the event
  static final String APPT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";

  final EncounterServiceTest.MemoryEncounters encounters =
      new EncounterServiceTest.MemoryEncounters();
  final ConditionServiceTest.MemoryConditions conditions =
      new ConditionServiceTest.MemoryConditions();
  final EncounterClosing closing = new EncounterClosing(encounters, conditions, CLOCK);

  void visit(String status) {
    encounters.arrived(ENC, PAT, status, NOW.minusSeconds(1800));
    encounters.rows.get(ENC).search().put("appointmentId", APPT);
  }

  void diagnosis(String category, String verification) {
    conditions.create(
        "c-" + conditions.rows.size(),
        PAT,
        new Document("resourceType", "Condition"),
        new Document("encounterId", ENC)
            .append("category", category)
            .append("verificationStatus", verification)
            .append("recordedDate", java.util.Date.from(NOW)),
        "User/doc-sub");
  }

  @Test
  void aDiagnosedVisitFinishesAndPublishesE6() {
    visit("in-progress");
    diagnosis("encounter-diagnosis", "confirmed");
    var v = closing.finish(ENC, DOC, "User/doc-sub");
    Encounter e = v.resource();
    assertThat(e.getStatus()).isEqualTo(Encounter.EncounterStatus.FINISHED);
    assertThat(e.getPeriod().getEnd().toInstant()).isEqualTo(NOW);
    assertThat(v.version()).isEqualTo(2);
    Document search = encounters.rows.get(ENC).search();
    assertThat(search.getString("status")).isEqualTo("finished");
    assertThat(search.getDate("periodEnd").toInstant()).isEqualTo(NOW);

    assertThat(encounters.events).singleElement().satisfies(this::isE6);
  }

  void isE6(CloudEvent evt) {
    assertThat(evt.type()).isEqualTo("shp.clinical.encounter-finished.v1");
    assertThat(evt.subject()).isEqualTo("Encounter/" + ENC);
    assertThat(EventSchemas.validate(evt)).isEmpty(); // producer matches the contract (CLN-27)
    var data = evt.data();
    assertThat(data.get("encounterId").asString()).isEqualTo(ENC);
    assertThat(data.get("appointmentId").asString()).isEqualTo(APPT);
    assertThat(data.get("patientId").asString()).isEqualTo(PAT);
    assertThat(data.get("practitionerId").asString()).isEqualTo(DOC);
    assertThat(data.get("finishedAt").asString()).isEqualTo(NOW.toString());
    assertThat(data.get("version").asInt()).isEqualTo(2);
  }

  @Test
  void withoutADiagnosisIt422sAndNothingChanges() {
    visit("in-progress");
    assertThatThrownBy(() -> closing.finish(ENC, DOC, "User/doc-sub"))
        .isInstanceOf(EncounterClosing.DiagnosisRequiredException.class);
    assertThat(EncounterClosing.DIAGNOSIS_REQUIRED.code()).isEqualTo("diagnosis-required");
    assertThat(EncounterClosing.DIAGNOSIS_REQUIRED.status()).isEqualTo(422);
    assertThat(encounters.rows.get(ENC).version()).isEqualTo(1);
    assertThat(encounters.events).isEmpty();
  }

  @Test
  void anUnderlyingConditionOrAnErrorDoesNotCount() {
    visit("in-progress");
    diagnosis("problem-list-item", "confirmed");
    diagnosis("encounter-diagnosis", "entered-in-error");
    assertThatThrownBy(() -> closing.finish(ENC, DOC, "User/doc-sub"))
        .isInstanceOf(EncounterClosing.DiagnosisRequiredException.class);
    diagnosis("encounter-diagnosis", "provisional");
    assertThat(closing.finish(ENC, DOC, "User/doc-sub").version()).isEqualTo(2);
  }

  @Test
  void onlyAnInProgressVisitFinishes() {
    for (String status : new String[] {"arrived", "finished"}) {
      encounters.rows.clear();
      visit(status);
      diagnosis("encounter-diagnosis", "confirmed");
      assertThatThrownBy(() -> closing.finish(ENC, DOC, "User/doc-sub"))
          .isInstanceOfSatisfying(
              ShpException.class,
              e -> assertThat(e.info().code()).isEqualTo("invalid-state-transition"));
    }
  }

  @Test
  void aNonDoctorAndAnUnknownVisitAreRejected() {
    assertThatThrownBy(() -> closing.finish(ENC, null, "User/nurse"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().status()).isEqualTo(403));
    assertThatThrownBy(() -> closing.finish(ENC, DOC, "User/doc-sub"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("not-found"));
  }
}
