package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.bson.Document;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MarkdownType;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;
import org.shp.clinical.api.EncounterAddendumController;
import org.shp.web.errors.ShpException;

/** CLN-21 (US-029; 09 C15; AC-04.2): addenda after the visit ends. Fake data only. */
class EncounterAddendaTest {
  static final String ENC = EncounterServiceTest.ENC;
  static final String PAT = EncounterServiceTest.PAT;
  static final String DOC = EncounterServiceTest.DOC;
  static final Instant NOW = Instant.parse("2026-12-04T08:00:00Z");

  final EncounterServiceTest.MemoryEncounters encounters =
      new EncounterServiceTest.MemoryEncounters();
  final EncounterAddenda addenda =
      new EncounterAddenda(encounters, Clock.fixed(NOW, ZoneOffset.UTC));

  void finishedVisitWithNote() {
    encounters.arrived(ENC, PAT, "finished", NOW.minusSeconds(86_400));
    Document r = encounters.rows.get(ENC).resource();
    r.put("reasonCode", java.util.List.of(new Document("text", "Chest pain (test)")));
    r.put(
        "extension",
        java.util.List.of(
            new Document("url", EncounterService.CLINICAL_NOTE)
                .append("valueMarkdown", "Original note (test)")));
  }

  @Test
  void anAddendumIsAddedAndTheOriginalIsUnchanged() {
    finishedVisitWithNote();
    var v = addenda.add(ENC, "Lab result reviewed (test)", DOC, 1, "User/doc-sub");
    Encounter e = v.resource();
    assertThat(v.version()).isEqualTo(2);
    // TC-AC-AD.1: the original is unchanged
    assertThat(e.getReasonCodeFirstRep().getText()).isEqualTo("Chest pain (test)");
    assertThat(
            ((MarkdownType) e.getExtensionByUrl(EncounterService.CLINICAL_NOTE).getValue())
                .getValue())
        .isEqualTo("Original note (test)");
    assertThat(e.getStatus()).isEqualTo(Encounter.EncounterStatus.FINISHED);

    Extension a = e.getExtensionByUrl(EncounterService.ADDENDUM);
    assertThat(((Reference) a.getExtensionByUrl("author").getValue()).getReference())
        .isEqualTo("Practitioner/" + DOC);
    assertThat(a.getExtensionByUrl("recordedAt").getValueAsPrimitive().getValueAsString())
        .startsWith("2026-12-04T08:00:00");
    assertThat(((MarkdownType) a.getExtensionByUrl("text").getValue()).getValue())
        .isEqualTo("Lab result reviewed (test)");
  }

  @Test
  void addendaAccumulate() {
    finishedVisitWithNote();
    addenda.add(ENC, "first (test)", DOC, 1, "User/doc-sub");
    var v = addenda.add(ENC, "second (test)", DOC, 2, "User/doc-sub");
    assertThat(v.resource().getExtensionsByUrl(EncounterService.ADDENDUM)).hasSize(2);
    assertThat(v.version()).isEqualTo(3);
  }

  @Test
  void onlyAFinishedVisitTakesAnAddendum() {
    encounters.arrived(ENC, PAT, "in-progress", NOW);
    assertThatThrownBy(() -> addenda.add(ENC, "too early", DOC, 1, "User/doc-sub"))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("invalid-state-transition");
              assertThat(e.info().status()).isEqualTo(409);
            });
  }

  @Test
  void textIsOneTo4000Characters() {
    finishedVisitWithNote();
    for (String bad : new String[] {null, "", "   ", "x".repeat(4_001)}) {
      assertThatThrownBy(() -> addenda.add(ENC, bad, DOC, 1, "User/doc-sub"))
          .isInstanceOfSatisfying(
              ShpException.class, e -> assertThat(e.info().code()).isEqualTo("validation-failed"));
    }
    assertThat(addenda.add(ENC, "x".repeat(4_000), DOC, 1, "User/doc-sub").version()).isEqualTo(2);
  }

  @Test
  void aNonDoctorAndAnUnknownVisitAreRejected() {
    assertThatThrownBy(() -> addenda.add(ENC, "t", null, 1, "User/nurse"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().status()).isEqualTo(403));
    assertThatThrownBy(() -> addenda.add(ENC, "t", DOC, 1, "User/doc-sub"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("not-found"));
  }

  @Test
  void theBodyIsExactlyAText() {
    assertThat(EncounterAddendumController.text("{\"text\":\"hello\"}")).isEqualTo("hello");
    for (String bad :
        new String[] {
          "[]", "{}", "{\"text\":1}", "{\"text\":\"a\",\"author\":\"x\"}", "not json"
        }) {
      assertThatThrownBy(() -> EncounterAddendumController.text(bad))
          .isInstanceOf(ShpException.class);
    }
  }
}
