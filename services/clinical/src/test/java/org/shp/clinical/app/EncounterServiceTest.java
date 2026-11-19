package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MarkdownType;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvent;
import org.shp.web.errors.ShpException;

/** CLN-15 (US-024; 09 C11–C13; 01 §4.6): the Encounter API rules. Fake data only. */
public class EncounterServiceTest {
  public static final String ENC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e10";
  public static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  public static final String DOC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03";
  static final String OTHER_DOC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e04";
  static final Instant CHECKED_IN = Instant.parse("2026-12-03T01:15:00Z");

  /** In-memory {@code encounters} with the store's compare-and-set. */
  public static final class MemoryEncounters implements Encounters {
    public final Map<String, Stored> rows = new LinkedHashMap<>();
    public final List<CloudEvent> events = new ArrayList<>();
    final List<String> changedBy = new ArrayList<>();

    @Override
    public boolean existsForAppointment(String appointmentId) {
      return rows.values().stream()
          .anyMatch(s -> appointmentId.equals(s.search().getString("appointmentId")));
    }

    @Override
    public void create(String id, String patientId, Document resource, Document search, String by) {
      rows.put(id, new Stored(id, patientId, 1, resource, search));
    }

    @Override
    public Optional<Stored> find(String id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Stored update(
        Stored current,
        Document resource,
        Document search,
        int expectedVersion,
        String by,
        CloudEvent event) {
      Stored now = rows.get(current.id());
      if (now == null) {
        throw new ShpException("not-found");
      }
      if (now.version() != expectedVersion) {
        throw new ShpException("version-conflict");
      }
      Stored next = new Stored(now.id(), now.patientId(), expectedVersion + 1, resource, search);
      rows.put(now.id(), next);
      changedBy.add(by);
      if (event != null) {
        events.add(event);
      }
      return next;
    }

    @Override
    public List<Stored> byPatient(String patientId, String status, int limit) {
      return rows.values().stream()
          .filter(s -> s.patientId().equals(patientId))
          .filter(s -> status == null || status.equals(s.search().getString("status")))
          .sorted(Comparator.comparing((Stored s) -> s.search().getDate("periodStart")).reversed())
          .limit(limit)
          .toList();
    }

    /** Stores the check-in Encounter of CLN-14 with {@code status}. */
    public void arrived(String id, String patientId, String status, Instant at) {
      Document resource =
          EncounterIntake.resource(id, "appt-" + id, patientId, DOC, "cardiology", at);
      resource.put("status", status);
      Document search = EncounterIntake.search("appt-" + id, DOC, "cardiology", at);
      search.put("status", status);
      rows.put(id, new Stored(id, patientId, 1, resource, search));
    }
  }

  final MemoryEncounters encounters = new MemoryEncounters();
  final EncounterService service = new EncounterService(encounters);

  static String body(String extra) {
    return """
    { "resourceType": "Encounter", "id": "%s", "status": "finished", "class": { "code": "EMER" }%s }
    """
        .formatted(ENC, extra);
  }

  // ---------- C11 $start ----------

  @Test
  void theAppointmentsDoctorStartsTheVisit() {
    encounters.arrived(ENC, PAT, "arrived", CHECKED_IN);
    var v = service.start(ENC, DOC, "User/doc-sub");
    assertThat(v.resource().getStatus()).isEqualTo(Encounter.EncounterStatus.INPROGRESS);
    assertThat(EncounterService.attender(v.resource())).isEqualTo(DOC);
    assertThat(v.version()).isEqualTo(2);
    assertThat(encounters.rows.get(ENC).search().getString("status")).isEqualTo("in-progress");
    assertThat(encounters.changedBy).containsExactly("User/doc-sub");
  }

  @Test
  void anotherDoctorCannotStartIt() {
    encounters.arrived(ENC, PAT, "arrived", CHECKED_IN);
    assertThatThrownBy(() -> service.start(ENC, OTHER_DOC, "User/x"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().status()).isEqualTo(403));
    assertThatThrownBy(() -> service.start(ENC, null, "User/nurse"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().status()).isEqualTo(403));
    assertThat(encounters.rows.get(ENC).version()).isEqualTo(1);
  }

  @Test
  void onlyAnArrivedVisitCanStart() {
    encounters.arrived(ENC, PAT, "in-progress", CHECKED_IN);
    assertThatThrownBy(() -> service.start(ENC, DOC, "User/doc-sub"))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> assertThat(e.info().code()).isEqualTo("invalid-state-transition"));
  }

  @Test
  void anUnknownEncounterIs404() {
    assertThatThrownBy(() -> service.start(ENC, DOC, "User/x"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("not-found"));
    assertThatThrownBy(() -> service.read(ENC)).isInstanceOf(ShpException.class);
  }

  // ---------- C12 read / list ----------

  @Test
  void readCarriesIdAndVersion() {
    encounters.arrived(ENC, PAT, "arrived", CHECKED_IN);
    var v = service.read(ENC);
    assertThat(v.resource().getIdElement().getIdPart()).isEqualTo(ENC);
    assertThat(v.resource().getMeta().getVersionId()).isEqualTo("1");
    assertThat(v.version()).isEqualTo(1);
  }

  @Test
  void aPatientListsOnlyFinishedVisitsNewestFirst() {
    encounters.arrived("e1", PAT, "finished", CHECKED_IN);
    encounters.arrived("e2", PAT, "in-progress", CHECKED_IN.plusSeconds(60));
    encounters.arrived("e3", PAT, "finished", CHECKED_IN.plusSeconds(120));
    encounters.arrived("e4", "someone-else", "finished", CHECKED_IN);
    assertThat(service.byPatient(PAT, true, 20))
        .extracting(e -> e.getIdElement().getIdPart())
        .containsExactly("e3", "e1");
    assertThat(service.byPatient(PAT, false, 2))
        .extracting(e -> e.getIdElement().getIdPart())
        .containsExactly("e3", "e2");
  }

  // ---------- C13 edit ----------

  @Test
  void reasonNoteAndDiagnosisAreEdited() {
    encounters.arrived(ENC, PAT, "in-progress", CHECKED_IN);
    String b =
        body(
            """
            , "reasonCode": [ { "text": "Chest pain for 2 days" } ],
              "diagnosis": [ { "condition": { "reference": "Condition/c1" } } ],
              "extension": [ { "url": "%s", "valueMarkdown": "History: none (test)" } ]
            """
                .formatted(EncounterService.CLINICAL_NOTE));
    var v = service.updateNote(ENC, b, 1, "User/doc-sub");
    Encounter e = v.resource();
    assertThat(e.getReasonCodeFirstRep().getText()).isEqualTo("Chest pain for 2 days");
    assertThat(e.getDiagnosisFirstRep().getCondition().getReference()).isEqualTo("Condition/c1");
    assertThat(
            ((MarkdownType) e.getExtensionByUrl(EncounterService.CLINICAL_NOTE).getValue())
                .getValue())
        .isEqualTo("History: none (test)");
    assertThat(v.version()).isEqualTo(2);
  }

  @Test
  void serverOwnedElementsKeepTheirStoredValue() {
    encounters.arrived(ENC, PAT, "in-progress", CHECKED_IN);
    // The body claims status finished and class EMER: both are ignored (TC-SEC-03).
    Encounter e = service.updateNote(ENC, body(""), 1, "User/doc-sub").resource();
    assertThat(e.getStatus()).isEqualTo(Encounter.EncounterStatus.INPROGRESS);
    assertThat(e.getClass_().getCode()).isEqualTo("AMB");
    assertThat(e.getSubject().getReference()).isEqualTo("Patient/" + PAT);
    assertThat(EncounterService.attender(e)).isEqualTo(DOC);
    assertThat(encounters.rows.get(ENC).search().getString("status")).isEqualTo("in-progress");
  }

  @Test
  void aFinishedVisitIs409AndUnchanged() {
    encounters.arrived(ENC, PAT, "finished", CHECKED_IN);
    String b = body(", \"reasonCode\": [ { \"text\": \"changed later\" } ]");
    assertThatThrownBy(() -> service.updateNote(ENC, b, 1, "User/doc-sub"))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("invalid-state-transition");
              assertThat(e.info().status()).isEqualTo(409);
            });
    assertThat(encounters.rows.get(ENC).version()).isEqualTo(1);
    assertThat(encounters.rows.get(ENC).resource().containsKey("reasonCode")).isFalse();
  }

  @Test
  void aStaleVersionIs412() {
    encounters.arrived(ENC, PAT, "in-progress", CHECKED_IN);
    assertThatThrownBy(() -> service.updateNote(ENC, body(""), 3, "User/doc-sub"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("version-conflict"));
  }

  @Test
  void addendaAreKeptAndCannotBeAddedThroughPut() {
    encounters.arrived(ENC, PAT, "in-progress", CHECKED_IN);
    Document stored = encounters.rows.get(ENC).resource();
    stored.put(
        "extension",
        List.of(
            new Document("url", EncounterService.ADDENDUM)
                .append(
                    "extension",
                    List.of(new Document("url", "text").append("valueMarkdown", "kept")))));
    String b =
        body(
            ", \"extension\": [ { \"url\": \"%s\", \"extension\": [ { \"url\": \"text\", \"valueMarkdown\": \"sneaked\" } ] } ]"
                .formatted(EncounterService.ADDENDUM));
    Encounter e = service.updateNote(ENC, b, 1, "User/doc-sub").resource();
    assertThat(e.getExtensionsByUrl(EncounterService.ADDENDUM)).hasSize(1);
    assertThat(FhirTexts.of(e)).contains("kept").doesNotContain("sneaked");
  }

  @Test
  void bodyRulesAreChecked() {
    encounters.arrived(ENC, PAT, "in-progress", CHECKED_IN);
    assertInvalid(
        body(", \"reasonCode\": [ { \"coding\": [ { \"code\": \"x\" } ], \"text\": \"t\" } ]"),
        "Encounter.reasonCode");
    assertInvalid(
        body(", \"reasonCode\": [ { \"text\": \"" + "x".repeat(501) + "\" } ]"),
        "Encounter.reasonCode.text");
    assertInvalid(
        body(", \"diagnosis\": [ { \"condition\": { \"reference\": \"Observation/o1\" } } ]"),
        "Encounter.diagnosis");
    assertInvalid(
        body(
            ", \"extension\": [ { \"url\": \"https://example.test/other\", \"valueString\": \"x\" } ]"),
        "Encounter.extension");
    assertInvalid(
        body(
            ", \"extension\": [ { \"url\": \"%1$s\", \"valueMarkdown\": \"a\" }, { \"url\": \"%1$s\", \"valueMarkdown\": \"b\" } ]"
                .formatted(EncounterService.CLINICAL_NOTE)),
        "Encounter.extension[clinical-note] must appear at most once");
    assertInvalid(
        body(
            ", \"extension\": [ { \"url\": \"%s\", \"valueString\": \"a\" } ]"
                .formatted(EncounterService.CLINICAL_NOTE)),
        "Encounter.extension[clinical-note] must have a valueMarkdown");
    assertInvalid(
        body("").replace(ENC, "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e99"), "Encounter.id must match");
    assertThat(encounters.rows.get(ENC).version()).isEqualTo(1);
  }

  void assertInvalid(String b, String detail) {
    assertThatThrownBy(() -> service.updateNote(ENC, b, 1, "User/doc-sub"))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("validation-failed");
              assertThat(e.detail()).startsWith(detail);
            });
  }

  /** Serialized resource text, for content assertions. */
  static final class FhirTexts {
    static String of(Encounter e) {
      return org.shp.fhir.FhirJson.serialize(e);
    }
  }
}
