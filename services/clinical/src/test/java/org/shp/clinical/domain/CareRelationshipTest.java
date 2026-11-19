package org.shp.clinical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.shp.clinical.domain.CareRelationship.Source;

/** CLN-13 (15 §4): sources, versions and validity of a care relationship. */
class CareRelationshipTest {
  static final Instant T1 = Instant.parse("2026-12-03T01:15:00Z");
  static final Instant T2 = Instant.parse("2026-12-10T01:15:00Z");

  static Source appt(String id, String status, Instant at, long version) {
    return new Source(CareRelationship.APPOINTMENT, id, status, at, version);
  }

  final CareRelationship empty = CareRelationship.empty("p1", "q1");

  @Test
  void idIsPractitionerThenPatient() {
    assertThat(empty.id()).isEqualTo("p1|q1");
  }

  @Test
  void noSourceIsNoCare() {
    assertThat(empty.validUntil()).isEmpty();
  }

  @Test
  void validUntilIsTheLatestCountingSourcePlus365Days() {
    var r = empty.with(appt("a1", "booked", T1, 1)).with(appt("a2", "booked", T2, 1));
    assertThat(r.validUntil()).contains(T2.plus(CareRelationship.VALIDITY));
    assertThat(r.sources()).extracting(Source::id).containsExactly("a1", "a2");
  }

  @Test
  void encounterSourcesCountToo() {
    var r = empty.with(new Source(CareRelationship.ENCOUNTER, "e1", "arrived", T2, 1));
    assertThat(r.validUntil()).contains(T2.plus(CareRelationship.VALIDITY));
  }

  @Test
  void cancelledAndNoShowSourcesDoNotCount() {
    var r =
        empty
            .with(appt("a1", "booked", T1, 1))
            .with(appt("a2", "booked", T2, 1))
            .withStatus(CareRelationship.APPOINTMENT, "a2", "cancelled", 2);
    assertThat(r.validUntil()).contains(T1.plus(CareRelationship.VALIDITY));
    r = r.withStatus(CareRelationship.APPOINTMENT, "a1", "noshow", 2);
    assertThat(r.validUntil()).isEmpty();
  }

  @Test
  void anOlderOrEqualVersionIsIgnored() {
    var r = empty.with(appt("a1", "cancelled", T1, 2));
    assertThat(r.with(appt("a1", "booked", T1, 2))).isSameAs(r);
    assertThat(r.with(appt("a1", "booked", T1, 1))).isSameAs(r);
    assertThat(r.withStatus(CareRelationship.APPOINTMENT, "a1", "fulfilled", 2)).isSameAs(r);
  }

  @Test
  void aNewerVersionReplacesTheSource() {
    var r = empty.with(appt("a1", "booked", T1, 1)).with(appt("a1", "booked", T2, 2));
    assertThat(r.sources()).singleElement().extracting(Source::at).isEqualTo(T2);
  }

  @Test
  void statusOfAnUnknownSourceIsUnchanged() {
    assertThat(empty.withStatus(CareRelationship.APPOINTMENT, "a9", "cancelled", 5))
        .isSameAs(empty);
    assertThat(empty.ended(CareRelationship.APPOINTMENT, "a9", "cancelled")).isSameAs(empty);
  }

  @Test
  void endedIgnoresTheVersionButKeepsIt() {
    var r =
        empty
            .with(appt("a1", "booked", T1, 7))
            .ended(CareRelationship.APPOINTMENT, "a1", "cancelled");
    assertThat(r.sources())
        .singleElement()
        .satisfies(s -> {
          assertThat(s.status()).isEqualTo("cancelled");
          assertThat(s.version()).isEqualTo(7);
        });
    assertThat(r.validUntil()).isEmpty();
  }

  @Test
  void endedNeedsAnEndingStatus() {
    assertThatThrownBy(() -> empty.ended(CareRelationship.APPOINTMENT, "a1", "booked"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
