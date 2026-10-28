package org.shp.events.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.shp.authz.AuthzDecision;
import org.shp.authz.AuthzMatrix;
import org.shp.authz.AuthzRule;
import org.shp.authz.Principal;
import org.shp.events.CloudEvents;
import org.shp.events.EventSchemas;

class AuditTest {
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T02:15:00Z"), ZoneOffset.UTC);
  static final AuditRecord.Actor DOCTOR =
      new AuditRecord.Actor(
          "kc-doctor", "doctor", "Practitioner/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02");
  static final UUID PATIENT = UUID.fromString("0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01");

  static AuditRecord build(String resource, String ip, String reason) {
    return AuditRecordBuilder.build(
        CLOCK,
        "clinical",
        "read",
        "success",
        DOCTOR,
        resource,
        PATIENT,
        "TREAT",
        reason,
        "4bf92f3577b34da6a3ce929d0e0e4736",
        ip,
        "Mozilla/5.0");
  }

  @Test
  void builtRecordIsAValidE11Event() {
    var evt =
        build("Encounter/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e05", "10.1.2.3", null)
            .toCloudEvent(CLOCK);
    assertThat(evt.type()).isEqualTo("shp.audit.recorded.v1");
    assertThat(EventSchemas.validate(evt)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "10.1.2.3, 10.1.2.x",
    "192.168.255.254, 192.168.255.x",
    "::ffff:10.9.8.7, 10.9.8.x",
    "2001:db8:1:2:3:4:5:6, 2001:0db8:0001::x"
  })
  void ipIsMasked(String ip, String expected) {
    assertThat(AuditRecordBuilder.maskIp(ip)).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"not-an-ip", "example.com", "999.1.1.1"})
  void garbageIpIsNotStoredAndNeverResolved(String ip) {
    assertThat(AuditRecordBuilder.maskIp(ip)).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Patient?name=Nguyen",
        "Patient?phone=0901234567",
        "Patient/0192 f8a4",
        "Patient search",
        ""
      })
  void resourceWithValuesOrSpacesIsRejected(String resource) {
    assertThatThrownBy(() -> build(resource, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Patient",
        "Patient?name&birthdate",
        "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70"
      })
  void resourceWithTypeIdOrParameterNamesIsAccepted(String resource) {
    assertThat(build(resource, null, null).resource()).isEqualTo(resource);
  }

  @Test
  void longReasonIsRejectedAndLongUserAgentTruncated() {
    assertThatThrownBy(
            () -> build("Patient", null, "x".repeat(AuditRecordBuilder.MAX_REASON_LENGTH + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    var r =
        AuditRecordBuilder.build(
            CLOCK,
            "s",
            "read",
            "success",
            DOCTOR,
            "Patient",
            null,
            null,
            null,
            null,
            null,
            "u".repeat(500));
    assertThat(r.client().userAgent()).hasSize(AuditRecordBuilder.MAX_USER_AGENT_LENGTH);
  }

  @Test
  void unknownActionOrOutcomeIsRejected() {
    assertThatThrownBy(
            () ->
                AuditRecordBuilder.build(
                    CLOCK, "s", "peek", "success", DOCTOR, "Patient", null, null, null, null, null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AuditRecordBuilder.build(
                    CLOCK, "s", "read", "maybe", DOCTOR, "Patient", null, null, null, null, null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyMatrixActionMapsToAValidAuditAction() {
    AuthzMatrix.loadEmbedded().rules().stream()
        .map(AuthzRule::action)
        .distinct()
        .forEach(
            a -> {
              assertThat(AuthzAudit.ACTION_MAP).as("thiếu ánh xạ audit cho '%s'", a).containsKey(a);
              assertThat(AuditActions.ALL).contains(AuthzAudit.ACTION_MAP.get(a));
            });
  }

  @Test
  void deniedDecisionIsAuditedWithReasonAndAllowedWithPurpose() {
    var p = new Principal("kc-doctor", Set.of("doctor"), "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02");
    var denied =
        AuthzAudit.fromDecision(
            CLOCK,
            "clinical",
            p,
            "Practitioner",
            "read",
            "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01",
            AuthzDecision.deny("no-care-relationship"),
            PATIENT,
            null,
            "10.0.0.5",
            "ua",
            null);
    assertThat(denied.outcome()).isEqualTo("denied");
    assertThat(denied.reason()).isEqualTo("no-care-relationship");
    assertThat(denied.actor().fhirUser())
        .isEqualTo("Practitioner/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02");

    var allowed =
        AuthzAudit.fromDecision(
            CLOCK,
            "clinical",
            p,
            "Practitioner",
            "read",
            "Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01",
            new AuthzDecision(true, "PAT-READ-DOC", "TREAT", List.of(), List.of(), null),
            PATIENT,
            null,
            "10.0.0.5",
            "ua",
            null);
    assertThat(allowed.outcome()).isEqualTo("success");
    assertThat(allowed.purposeOfUse()).isEqualTo("TREAT");
    assertThat(allowed.reason()).isNull();
    assertThat(EventSchemas.validate(allowed.toCloudEvent(CLOCK))).isEmpty();
  }

  @Test
  void actorRolePrefersTheMostPrivileged() {
    var r =
        AuthzAudit.fromDecision(
            CLOCK,
            "s",
            new Principal("s", Set.of("patient", "nurse"), null),
            "Practitioner",
            "read",
            "Patient",
            new AuthzDecision(true, "X", null, List.of(), List.of(), null),
            null,
            null,
            null,
            null,
            null);
    assertThat(r.actor().role()).isEqualTo("nurse");
    assertThat(r.actor().fhirUser()).isNull();
  }

  @Test
  void inMemorySinkKeepsRecords() {
    var sink = new InMemoryAuditSink();
    sink.emit(build("Patient", null, null));
    assertThat(sink.records()).hasSize(1);
    sink.clear();
    assertThat(sink.records()).isEmpty();
  }

  @Test
  void serializedRecordHasNoNullNoise() {
    var json = CloudEvents.toJson(build("Patient", null, null).toCloudEvent(CLOCK));
    assertThat(json).doesNotContain("\"reason\"").contains("\"purposeOfUse\":\"TREAT\"");
  }
}
