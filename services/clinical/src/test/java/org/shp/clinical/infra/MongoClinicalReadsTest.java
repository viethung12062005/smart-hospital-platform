package org.shp.clinical.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.clinical.app.ClinicalReads.CareRow;
import org.shp.clinical.app.ClinicalReads.PatientFacts;
import org.shp.clinical.app.ClinicalReads.RoleView;

/** CLN-12: mapping of the 03 §2.2 documents the internal API reads. Fake data only. */
class MongoClinicalReadsTest {

  @Test
  void patientFactsComeFromTheStoredResource() {
    Document resource =
        Document.parse(
            """
            {
              "resourceType": "Patient",
              "birthDate": "1985-03-14",
              "telecom": [
                { "system": "phone", "value": "+84900012345" },
                { "system": "email", "value": "an.nguyen@example.test" }
              ],
              "communication": [
                { "language": { "coding": [ { "system": "urn:ietf:bcp:47", "code": "en" } ] } }
              ]
            }
            """);
    assertThat(MongoClinicalReads.toFacts(resource))
        .isEqualTo(
            new PatientFacts(
                LocalDate.parse("1985-03-14"), "+84900012345", "an.nguyen@example.test", "en"));
  }

  @Test
  void missingFieldsAreNull() {
    assertThat(MongoClinicalReads.toFacts(new Document("resourceType", "Patient")))
        .isEqualTo(new PatientFacts(null, null, null, null));
  }

  @Test
  void aPartialBirthDateGivesNoDate() {
    assertThat(MongoClinicalReads.toFacts(new Document("birthDate", "1985")).birthDate()).isNull();
  }

  @Test
  void roleViewComesFromTheSearchFields() {
    Document stored =
        new Document("_id", "r1")
            .append(
                "search",
                new Document("practitionerId", "p1")
                    .append("specialty", "cardiology")
                    .append("code", "doctor")
                    .append("active", false));
    assertThat(MongoClinicalReads.toRole(stored))
        .isEqualTo(new RoleView("r1", "cardiology", "doctor", false));
  }

  @Test
  void careViaIsTheMostRecentSourceThatIsNotCancelled() {
    Date until = Date.from(Instant.parse("2027-11-20T02:00:00Z"));
    Document row =
        new Document("_id", "p1|q1")
            .append("validUntil", until)
            .append(
                "sources",
                List.of(
                    source("appointment", "booked", "2026-11-10T02:00:00Z"),
                    source("encounter", "in-progress", "2026-11-12T02:00:00Z"),
                    source("appointment", "cancelled", "2026-11-15T02:00:00Z")));
    assertThat(MongoClinicalReads.toCare(row))
        .isEqualTo(new CareRow(until.toInstant(), "encounter"));
  }

  @Test
  void careIdIsPractitionerThenPatient() {
    assertThat(MongoClinicalReads.careId("p1", "q1")).isEqualTo("p1|q1");
  }

  private static Document source(String type, String status, String at) {
    return new Document("type", type)
        .append("id", type + "-1")
        .append("status", status)
        .append("at", Date.from(Instant.parse(at)));
  }
}
