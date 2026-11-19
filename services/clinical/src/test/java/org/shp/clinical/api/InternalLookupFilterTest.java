package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.shp.clinical.api.InternalLookupFilter.Route;
import org.shp.clinical.app.ClinicalReads.RoleView;
import org.shp.clinical.app.InternalLookups;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.InMemoryAuditSink;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** CLN-12 (09 §4, 15 §7): who may call each internal lookup, and what it answers. Fake data. */
class InternalLookupFilterTest {
  static final String PRAC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZoneOffset.UTC);

  final InternalLookups lookups = mock(InternalLookups.class);
  final InMemoryAuditSink audit = new InMemoryAuditSink();
  final FilterChain chain = mock(FilterChain.class);
  final InternalLookupFilter filter =
      new InternalLookupFilter(
          lookups,
          audit,
          CLOCK,
          "internal.clinical",
          Map.of(
              Route.CARE, Set.of("svc-document", "svc-ai", "svc-notification"),
              Route.ROLES, Set.of("svc-scheduling"),
              Route.CONTACT, Set.of("svc-notification"),
              Route.AGE_BAND, Set.of("svc-scheduling")));

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  static void signIn(String azp, String... roles) {
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject("service-account-" + azp)
            .claim("azp", azp)
            .claim("roles", List.of(roles))
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  MockHttpServletResponse get(String uri, String... params) throws Exception {
    var req = new MockHttpServletRequest("GET", uri);
    for (int i = 0; i + 1 < params.length; i += 2) {
      req.addParameter(params[i], params[i + 1]);
    }
    var res = new MockHttpServletResponse();
    filter.doFilter(req, res, chain);
    return res;
  }

  // ---------- routing ----------

  @Test
  void otherPathsPassThrough() throws Exception {
    signIn("svc-scheduling", "internal.clinical");
    get("/internal/v1/identity/abc");
    get("/fhir/Patient/" + PAT);
    get("/internal/v1/patients/" + PAT);
    verify(chain, times(3)).doFilter(any(), any());
    verifyNoInteractions(lookups);
  }

  // ---------- who may call ----------

  @Test
  void noTokenIs401() throws Exception {
    var res = get("/internal/v1/patients/" + PAT + "/age-band");
    assertThat(res.getStatus()).isEqualTo(401);
    verifyNoInteractions(lookups);
  }

  @ParameterizedTest
  @CsvSource({
    // route path                                        , caller allowed elsewhere only
    "/internal/v1/care-relationships/check               , svc-scheduling",
    "/internal/v1/practitioners/" + PRAC + "/roles        , svc-notification",
    "/internal/v1/patients/" + PAT + "/contact            , svc-scheduling",
    "/internal/v1/patients/" + PAT + "/age-band           , svc-document",
  })
  void aCallerNotListedForTheRouteIs403(String path, String caller) throws Exception {
    signIn(caller, "internal.clinical");
    var res = get(path, "practitioner", PRAC, "patient", PAT);
    assertThat(res.getStatus()).isEqualTo(403);
    verifyNoInteractions(lookups);
  }

  @Test
  void theTargetRoleIsRequired() throws Exception {
    signIn("svc-scheduling", "internal.scheduling");
    assertThat(get("/internal/v1/patients/" + PAT + "/age-band").getStatus()).isEqualTo(403);
  }

  @Test
  void aUserTokenIs403() throws Exception {
    signIn("shp-web", "doctor");
    assertThat(
            get("/internal/v1/care-relationships/check", "practitioner", PRAC, "patient", PAT)
                .getStatus())
        .isEqualTo(403);
    verifyNoInteractions(lookups);
  }

  @Test
  void onlyGetIsAnswered() throws Exception {
    signIn("svc-scheduling", "internal.clinical");
    var req = new MockHttpServletRequest("POST", "/internal/v1/patients/" + PAT + "/age-band");
    var res = new MockHttpServletResponse();
    filter.doFilter(req, res, chain);
    assertThat(res.getStatus()).isEqualTo(400);
    verifyNoInteractions(lookups);
  }

  // ---------- care check ----------

  @Test
  void careCheckReturnsViaAndValidUntil() throws Exception {
    signIn("svc-document", "internal.clinical");
    when(lookups.care(PRAC, PAT))
        .thenReturn(
            new InternalLookups.CareCheck(
                true, "appointment", Instant.parse("2027-11-20T02:00:00Z")));
    var res = get("/internal/v1/care-relationships/check", "practitioner", PRAC, "patient", PAT);
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(res.getContentAsString())
        .isEqualTo(
            "{\"care\":true,\"via\":\"appointment\",\"validUntil\":\"2027-11-20T02:00:00Z\"}");
  }

  @Test
  void noCareIsOnlyTheFlag() throws Exception {
    signIn("svc-ai", "internal.clinical");
    when(lookups.care(PRAC, PAT)).thenReturn(new InternalLookups.CareCheck(false, null, null));
    assertThat(
            get("/internal/v1/care-relationships/check", "practitioner", PRAC, "patient", PAT)
                .getContentAsString())
        .isEqualTo("{\"care\":false}");
  }

  @Test
  void careCheckNeedsBothIds() throws Exception {
    signIn("svc-notification", "internal.clinical");
    assertThat(get("/internal/v1/care-relationships/check", "practitioner", PRAC).getStatus())
        .isEqualTo(400);
    assertThat(
            get("/internal/v1/care-relationships/check", "practitioner", PRAC, "patient", "a b")
                .getStatus())
        .isEqualTo(400);
    verifyNoInteractions(lookups);
  }

  // ---------- practitioner roles ----------

  @Test
  void rolesAreListed() throws Exception {
    signIn("svc-scheduling", "internal.clinical");
    when(lookups.roles(PRAC))
        .thenReturn(Optional.of(List.of(new RoleView("r1", "cardiology", "doctor", true))));
    var res = get("/internal/v1/practitioners/" + PRAC + "/roles");
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentAsString())
        .isEqualTo(
            "[{\"id\":\"r1\",\"specialty\":\"cardiology\",\"code\":\"doctor\",\"active\":true}]");
  }

  @Test
  void rolesOfAnUnknownPractitionerAre404() throws Exception {
    signIn("svc-scheduling", "internal.clinical");
    when(lookups.roles(PRAC)).thenReturn(Optional.empty());
    assertThat(get("/internal/v1/practitioners/" + PRAC + "/roles").getStatus()).isEqualTo(404);
  }

  @Test
  void anIdThatCannotExistIs404WithoutALookup() throws Exception {
    signIn("svc-scheduling", "internal.clinical");
    assertThat(get("/internal/v1/practitioners/" + "x".repeat(65) + "/roles").getStatus())
        .isEqualTo(404);
    verify(lookups, never()).roles(anyString());
  }

  // ---------- contact ----------

  @Test
  void contactIsReturnedAndAudited() throws Exception {
    signIn("svc-notification", "internal.clinical");
    when(lookups.contact(PAT))
        .thenReturn(
            Optional.of(new InternalLookups.Contact("+84900012345", "an@example.test", "vi")));
    var res = get("/internal/v1/patients/" + PAT + "/contact");
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentAsString())
        .isEqualTo("{\"phone\":\"+84900012345\",\"email\":\"an@example.test\",\"locale\":\"vi\"}");

    assertThat(audit.records()).hasSize(1);
    AuditRecord r = audit.records().get(0);
    assertThat(r.action()).isEqualTo("read");
    assertThat(r.outcome()).isEqualTo("success");
    assertThat(r.service()).isEqualTo("clinical");
    assertThat(r.actor())
        .isEqualTo(new AuditRecord.Actor("service-account-svc-notification", "system", null));
    assertThat(r.resource()).isEqualTo("Patient/" + PAT);
    assertThat(r.patientId()).isEqualTo(UUID.fromString(PAT));
    assertThat(r.toString()).doesNotContain("+84900012345").doesNotContain("an@example.test");
  }

  @Test
  void aDeniedContactCallIsAuditedToo() throws Exception {
    signIn("svc-scheduling", "internal.clinical");
    assertThat(get("/internal/v1/patients/" + PAT + "/contact").getStatus()).isEqualTo(403);
    assertThat(audit.records())
        .singleElement()
        .extracting(AuditRecord::outcome)
        .isEqualTo("denied");
    verifyNoInteractions(lookups);
  }

  @Test
  void contactOfAnUnknownPatientIs404AndAudited() throws Exception {
    signIn("svc-notification", "internal.clinical");
    when(lookups.contact(PAT)).thenReturn(Optional.empty());
    assertThat(get("/internal/v1/patients/" + PAT + "/contact").getStatus()).isEqualTo(404);
    assertThat(audit.records()).hasSize(1);
  }

  @Test
  void aNonUuidPatientIdIsAuditedWithoutPatientId() throws Exception {
    signIn("svc-notification", "internal.clinical");
    when(lookups.contact("legacy-1")).thenReturn(Optional.empty());
    get("/internal/v1/patients/legacy-1/contact");
    AuditRecord r = audit.records().get(0);
    assertThat(r.resource()).isEqualTo("Patient/legacy-1");
    assertThat(r.patientId()).isNull();
  }

  @Test
  void otherRoutesWriteNoAudit() throws Exception {
    signIn("svc-scheduling", "internal.clinical");
    when(lookups.ageBand(PAT)).thenReturn(Optional.of("30-39"));
    get("/internal/v1/patients/" + PAT + "/age-band");
    assertThat(audit.records()).isEmpty();
  }

  // ---------- age band ----------

  @Test
  void ageBandIsReturned() throws Exception {
    signIn("svc-scheduling", "internal.clinical");
    when(lookups.ageBand(PAT)).thenReturn(Optional.of("30-39"));
    var res = get("/internal/v1/patients/" + PAT + "/age-band");
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentAsString()).isEqualTo("{\"ageBand\":\"30-39\"}");
  }

  @Test
  void ageBandOfAnUnknownPatientIs404() throws Exception {
    signIn("svc-scheduling", "internal.clinical");
    when(lookups.ageBand(PAT)).thenReturn(Optional.empty());
    assertThat(get("/internal/v1/patients/" + PAT + "/age-band").getStatus()).isEqualTo(404);
  }
}
