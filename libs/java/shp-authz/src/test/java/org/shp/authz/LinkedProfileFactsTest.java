package org.shp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LinkedProfileFactsTest {
  private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  private static final Principal CALLER =
      new Principal(
          "s",
          Set.of("patient"),
          "me",
          List.of(
              new LinkedProfile("child", "guardian", "full", null),
              new LinkedProfile("relative", "proxy", "booking", NOW.plusSeconds(60)),
              new LinkedProfile("lapsed", "proxy", "full", NOW.minusSeconds(1))));

  private static ResourceContext facts(String patientId) {
    return ResourceContext.builder()
        .patientId(patientId)
        .linkedFrom(CALLER, patientId, NOW)
        .build();
  }

  @Test
  void guardianWithFullScopeHoldsAllThreeFacts() {
    var c = facts("child");
    assertThat(c.linkedProfile()).isTrue();
    assertThat(c.linkedRecords()).isTrue();
    assertThat(c.guardianLink()).isTrue();
  }

  @Test
  void bookingScopeProxyIsLinkedButNotLinkedRecordsNorGuardian() {
    var c = facts("relative");
    assertThat(c.linkedProfile()).isTrue();
    assertThat(c.linkedRecords()).isFalse();
    assertThat(c.guardianLink()).isFalse();
  }

  @Test
  void expiredUnknownAndMissingLinksHoldNothing() {
    for (String id : new String[] {"lapsed", "stranger", null}) {
      var c = facts(id);
      assertThat(c.linkedProfile()).as(String.valueOf(id)).isFalse();
      assertThat(c.linkedRecords()).isFalse();
      assertThat(c.guardianLink()).isFalse();
    }
  }

  @Test
  void linkIsNoLongerHonouredOnceItsExpiryPasses() {
    assertThat(CALLER.linkTo("relative", NOW.plusSeconds(59))).isPresent();
    assertThat(CALLER.linkTo("relative", NOW.plusSeconds(60))).isEmpty();
  }

  @Test
  void principalWithoutProfilesHasAnEmptyListNotNull() {
    assertThat(new Principal("s", Set.of("patient"), "me").linkedProfiles()).isEmpty();
    assertThat(new Principal("s", Set.of("patient"), "me", null).linkedProfiles()).isEmpty();
  }
}
