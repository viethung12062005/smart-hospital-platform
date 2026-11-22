package org.shp.clinical.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.clinical.app.ClinicalReads.CareRow;
import org.shp.clinical.domain.CareRelationship;
import org.shp.clinical.domain.CareRelationship.Source;

/** CLN-13: the {@code care_relationships} document (03 §2.2) and what the care check reads. */
class MongoCareRelationshipsTest {
  static final Instant T1 = Instant.parse("2026-12-03T01:15:00Z");

  @Test
  void documentRoundTrips() {
    var r =
        CareRelationship.empty("p1", "q1")
            .with(new Source("appointment", "a1", "booked", T1, 3))
            .with(new Source("encounter", "e1", "arrived", T1.plusSeconds(60), 1));
    Document d = MongoCareRelationships.toDocument(r);
    assertThat(d.getString("_id")).isEqualTo("p1|q1");
    assertThat(d.getDate("validUntil"))
        .isEqualTo(Date.from(T1.plusSeconds(60).plus(CareRelationship.VALIDITY)));
    assertThat(MongoCareRelationships.fromDocument(d)).isEqualTo(r);
  }

  @Test
  void noCountingSourceStoresANullValidUntil() {
    var r =
        CareRelationship.empty("p1", "q1")
            .with(new Source("appointment", "a1", "cancelled", T1, 2));
    Document d = MongoCareRelationships.toDocument(r);
    assertThat(d.containsKey("validUntil")).isTrue();
    assertThat(d.get("validUntil")).isNull();
  }

  @Test
  void theCareCheckReadsTheSameDocument() {
    var r =
        CareRelationship.empty("p1", "q1")
            .with(new Source("appointment", "a1", "booked", T1, 1))
            .with(new Source("encounter", "e1", "arrived", T1.plusSeconds(3600), 1));
    assertThat(MongoClinicalReads.toCare(MongoCareRelationships.toDocument(r)))
        .isEqualTo(new CareRow(T1.plusSeconds(3600).plus(CareRelationship.VALIDITY), "encounter"));
  }

  @Test
  void aSourceWithoutVersionReadsAsZero() {
    Document d =
        new Document("practitionerId", "p1")
            .append("patientId", "q1")
            .append(
                "sources",
                List.of(
                    new Document("type", "appointment")
                        .append("id", "a1")
                        .append("status", "booked")
                        .append("at", Date.from(T1))));
    assertThat(MongoCareRelationships.fromDocument(d).sources())
        .singleElement()
        .extracting(Source::version)
        .isEqualTo(0L);
  }
}
