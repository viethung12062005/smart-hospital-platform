package org.shp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
  void changingThePatientClearsFactsDerivedForThePreviousPatient() {
    var context =
        ResourceContext.builder().linkedFrom(CALLER, "child", NOW).patientId("stranger").build();

    assertThat(context.patientId()).isEqualTo("stranger");
    assertThat(context.linkedProfile()).isFalse();
    assertThat(context.linkedRecords()).isFalse();
    assertThat(context.guardianLink()).isFalse();
  }

  @Test
  void principalWithoutProfilesHasAnEmptyListNotNull() {
    assertThat(new Principal("s", Set.of("patient"), "me").linkedProfiles()).isEmpty();
    assertThat(new Principal("s", Set.of("patient"), "me", null).linkedProfiles()).isEmpty();
  }

  // ---------- SHR-30: the delegation behind a decision, for the audit onBehalfOf ----------
  private static final AuthorizationEngine ENGINE =
      new AuthorizationEngine(AuthzMatrix.loadEmbedded(), Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void ownProfileHasNoDelegation() {
    var own = ResourceContext.builder().linkedFrom(CALLER, "me", NOW).build();
    assertThat(ENGINE.decide(CALLER, "Appointment", "book", own).allowed()).isTrue();
    assertThat(own.delegation(CALLER)).isEmpty();
  }

  @Test
  void linkedProfileAllowCarriesThePatientAndTheRelationship() {
    var guardian = facts("child");
    var allow = ENGINE.decide(CALLER, "Appointment", "book", guardian);
    assertThat(allow.allowed()).isTrue();
    assertThat(allow.ruleId()).isEqualTo("APPT-BOOK-LINKED");
    assertThat(guardian.delegation(CALLER))
        .contains(new ResourceContext.Delegation("child", "guardian"));

    var proxy = facts("relative");
    assertThat(ENGINE.decide(CALLER, "Appointment", "book", proxy).allowed()).isTrue();
    assertThat(proxy.delegation(CALLER))
        .contains(new ResourceContext.Delegation("relative", "proxy"));
  }

  @Test
  void linkedProfileDenyStillCarriesTheDelegation() {
    // A booking-scope proxy may not read allergies (linked-records), but the attempt was made for
    // the relative's record, so the denial is audited on their behalf as well.
    var proxy = facts("relative");
    var deny = ENGINE.decide(CALLER, "AllergyIntolerance", "read", proxy);
    assertThat(deny.allowed()).isFalse();
    assertThat(proxy.delegation(CALLER))
        .contains(new ResourceContext.Delegation("relative", "proxy"));
  }

  @Test
  void noLinkFactOrNoPatientMeansNoDelegation() {
    for (String id : new String[] {"lapsed", "stranger", null}) {
      assertThat(facts(id).delegation(CALLER)).as(String.valueOf(id)).isEmpty();
    }
    var doctor = new Principal("d", Set.of("doctor"), "dr");
    var care = ResourceContext.builder().patientId("child").hasCareRelationship(true).build();
    assertThat(care.delegation(doctor)).isEmpty();
    var linkedToSelf = ResourceContext.builder().patientId("me").linkedProfile(true).build();
    assertThat(linkedToSelf.delegation(CALLER)).isEmpty();
  }
}
